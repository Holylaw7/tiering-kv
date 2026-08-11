package io.tieringkv.txn.meta;

import io.tieringkv.cluster.rpc.MetaRaftRpc;
import io.tieringkv.transaction.lifecycle.TxnLifecycleState;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 网络化元数据 Raft 扩展（ADR-0099）：生命周期、决策序、快照追平、并发故障。 */
class MetadataNetworkRaftExtendedTest {

    @TempDir
    Path dir;

    private MetadataNetworkFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void start(int count) throws Exception {
        fixture = MetadataNetworkFixture.start(count, dir);
    }

    @Test
    void lifecycleProposeOverNetwork() throws Exception {
        start(3);
        fixture.awaitLeader();
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.lifecycle("t1", 1,
                        TxnLifecycleState.ACTIVE.name(), 99_999))).join();
        Thread.sleep(200);
        for (TxnMetadataNode node : fixture.nodes) {
            assertThat(node.state().lifecycleSnapshot().get("t1")
                    .expireAtMillis()).isEqualTo(99_999);
        }
    }

    @Test
    void rollbackProposeOverNetwork() throws Exception {
        start(3);
        fixture.awaitLeader();
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.rollback("t1"))).join();
        Thread.sleep(200);
        for (TxnMetadataNode node : fixture.nodes) {
            assertThat(node.state().get("t1").state())
                    .isEqualTo(TxnMetaEntry.State.ROLLED_BACK);
        }
    }

    @Test
    void decisionIndexMonotonic() throws Exception {
        start(3);
        fixture.awaitLeader();
        long previous = -1;
        for (int i = 0; i < 5; i++) {
            long index = fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
            assertThat(index).isGreaterThan(previous);
            previous = index;
        }
    }

    @Test
    void termMonotonicAcrossFailover() throws Exception {
        start(3);
        fixture.awaitLeader();
        int leaderIndex = fixture.leaderIndex();
        long beforeTerm = fixture.clientEndpoint
                .callMetaStatus(fixture.nodeIds.get(leaderIndex),
                        MetadataNetworkFixture.GROUP).join().term();
        fixture.nodes.get(leaderIndex).close();
        fixture.endpoints.get(leaderIndex).close();
        String newLeader = fixture.awaitNewLeader(
                fixture.nodeIds.get(leaderIndex));
        long afterTerm = fixture.clientEndpoint
                .callMetaStatus(newLeader, MetadataNetworkFixture.GROUP)
                .join().term();
        assertThat(afterTerm).isGreaterThanOrEqualTo(beforeTerm);
    }

    @Test
    void threeNodeRestartPreservesAll() throws Exception {
        start(3);
        fixture.awaitLeader();
        for (int i = 0; i < 3; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.commit("t2", 9))).join();
        Thread.sleep(200);
        fixture.close();
        fixture = MetadataNetworkFixture.startWithData(
                fixture.nodeIds, fixture.addresses, fixture.dataRoot);
        fixture.awaitLeader();
        Thread.sleep(300);
        for (TxnMetadataNode node : fixture.nodes) {
            assertThat(node.state().size()).isEqualTo(3);
            assertThat(node.state().get("t2").state())
                    .isEqualTo(TxnMetaEntry.State.COMMITTED);
        }
    }

    @Test
    void snapshotLeaderThenFollowerRejoins() throws Exception {
        start(3);
        fixture.awaitLeader();
        int followerIndex = fixture.nodeIds.stream()
                .map(id -> fixture.nodeIds.indexOf(id))
                .filter(i -> !fixture.nodeIds.get(i).equals(
                        fixture.leaderId()))
                .findFirst().orElseThrow();
        fixture.nodes.get(followerIndex).close();
        fixture.endpoints.get(followerIndex).close();
        for (int i = 0; i < 1_100; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        TxnMetadataNode restarted = fixture.restartNode(followerIndex);
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline
                && restarted.state().get("t1099") == null) {
            Thread.sleep(50);
        }
        assertThat(restarted.state().get("t1099")).isNotNull();
        assertThat(restarted.state().size()).isGreaterThanOrEqualTo(1_100);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {16, 256, 4096})
    void parameterizedProposalPayloadSize(int size) throws Exception {
        start(3);
        fixture.awaitLeader();
        byte[] primary = new byte[size];
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", primary, 1,
                        Map.of("r1", List.of())))).join();
        Thread.sleep(150);
        assertThat(fixture.client.leaderId()).isNotNull();
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state()
                .get("t1").primary()).hasSize(size);
    }

    @ParameterizedTest(name = "mutations {0}")
    @ValueSource(ints = {1, 8, 32})
    void parameterizedMutationPayload(int mutationCount) throws Exception {
        start(3);
        fixture.awaitLeader();
        Map<String, List<TxnMessages.Mutation>> regions = new LinkedHashMap<>();
        List<TxnMessages.Mutation> list = new ArrayList<>();
        for (int i = 0; i < mutationCount; i++) {
            list.add(new TxnMessages.Mutation(("k" + i).getBytes(),
                    ("v" + i).getBytes(), false));
        }
        regions.put("r1", list);
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        regions))).join();
        Thread.sleep(150);
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state()
                .get("t1").regionMutations().get("r1"))
                .hasSize(mutationCount);
    }

    @Test
    void fiveNodeFailoverProposes() throws Exception {
        start(5);
        fixture.awaitLeader();
        String firstLeader = fixture.leaderId();
        int leaderIndex = fixture.nodeIds.indexOf(firstLeader);
        fixture.nodes.get(leaderIndex).close();
        fixture.endpoints.get(leaderIndex).close();
        String newLeader = fixture.awaitNewLeader(firstLeader);
        assertThat(newLeader).isNotEqualTo(firstLeader);
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        Thread.sleep(200);
        int applied = 0;
        for (int i = 0; i < fixture.nodes.size(); i++) {
            if (i != leaderIndex
                    && fixture.nodes.get(i).state().get("t1") != null) {
                applied++;
            }
        }
        assertThat(applied).isGreaterThanOrEqualTo(3);
    }

    @Test
    void concurrentProposalsDuringFailover() throws Exception {
        start(3);
        fixture.awaitLeader();
        int writers = 4;
        int perWriter = 10;
        AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int base = w * perWriter;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perWriter; i++) {
                    int attempt = 0;
                    while (attempt < 5) {
                        try {
                            fixture.client.proposer().apply(TxnMetaCodec
                                    .encode(TxnMetaCommand.register(
                                            "f" + (base + i), new byte[]{1},
                                            base + i, Map.of("r1",
                                                    List.of())))).join();
                            break;
                        } catch (RuntimeException e) {
                            attempt++;
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                failures.incrementAndGet();
                                return;
                            }
                        }
                    }
                    if (attempt >= 5) {
                        failures.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        Thread.sleep(150);
        int leaderIndex = fixture.leaderIndex();
        fixture.nodes.get(leaderIndex).close();
        fixture.endpoints.get(leaderIndex).close();
        for (Thread worker : workers) {
            worker.join(15_000);
        }
        assertThat(failures.get()).isZero();
        Thread.sleep(300);
        int applied = 0;
        for (TxnMetadataNode node : fixture.nodes) {
            applied = Math.max(applied, node.state().size());
        }
        assertThat(applied).isGreaterThanOrEqualTo(writers * perWriter);
    }

    @Test
    void statusAfterFailoverReportsNewLeader() throws Exception {
        start(3);
        fixture.awaitLeader();
        String firstLeader = fixture.leaderId();
        int leaderIndex = fixture.nodeIds.indexOf(firstLeader);
        fixture.nodes.get(leaderIndex).close();
        fixture.endpoints.get(leaderIndex).close();
        String newLeader = fixture.awaitNewLeader(firstLeader);
        MetaRaftRpc.MetaRaftStatus status = fixture.clientEndpoint
                .callMetaStatus(newLeader, MetadataNetworkFixture.GROUP)
                .join();
        assertThat(status.leaderId()).isEqualTo(newLeader);
        assertThat(status.state()).isEqualTo("LEADER");
    }

    @Test
    void restartPreservesLifecycle() throws Exception {
        start(1);
        fixture.awaitLeader();
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.lifecycle("t1", 1,
                        TxnLifecycleState.PREWRITE.name(), 77_777))).join();
        TxnMetadataNode restarted = fixture.restartNode(0);
        fixture.awaitLeader();
        assertThat(restarted.state().lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.PREWRITE);
    }

    @Test
    void duplicateRegisterIdempotent() throws Exception {
        start(3);
        fixture.awaitLeader();
        byte[] command = TxnMetaCodec.encode(TxnMetaCommand.register(
                "t1", new byte[]{1}, 1, Map.of("r1", List.of())));
        long first = fixture.client.proposer().apply(command).join();
        long second = fixture.client.proposer().apply(command).join();
        assertThat(second).isGreaterThan(first);
        Thread.sleep(150);
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state().size())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "writers {0}")
    @ValueSource(ints = {2, 4, 8})
    void parameterizedConcurrentWriters(int writers) throws Exception {
        start(3);
        fixture.awaitLeader();
        int perWriter = 5;
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int base = w * perWriter;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perWriter; i++) {
                    fixture.client.proposer().apply(TxnMetaCodec.encode(
                            TxnMetaCommand.register("w" + (base + i),
                                    new byte[]{1}, base + i,
                                    Map.of("r1", List.of())))).join();
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        Thread.sleep(300);
        int applied = 0;
        for (TxnMetadataNode node : fixture.nodes) {
            applied = Math.max(applied, node.state().size());
        }
        assertThat(applied).isGreaterThanOrEqualTo(writers * perWriter);
    }

    @ParameterizedTest(name = "restart {0}")
    @ValueSource(ints = {1, 2})
    void parameterizedRepeatedRestart(int restarts) throws Exception {
        start(1);
        fixture.awaitLeader();
        fixture.client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        for (int i = 0; i < restarts; i++) {
            fixture.restartNode(0);
            fixture.awaitLeader();
        }
        assertThat(fixture.nodes.get(0).state().get("t1")).isNotNull();
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {100, 300, 600})
    void parameterizedProposalVolume(int txnCount) throws Exception {
        start(3);
        fixture.awaitLeader();
        for (int i = 0; i < txnCount; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        Thread.sleep(200);
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state().size())
                .isGreaterThanOrEqualTo(txnCount);
    }
}
