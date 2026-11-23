package io.tieringkv.benchmark.mvcc;

import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.txn.meta.MetadataNetworkFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 25 最终基准（ADR-0099 / TD-050）：网络化元数据提案吞吐、
 * failover、持久化恢复与快照恢复（进程内 TCP 等价，如实标注）。
 */
class Phase25BenchmarkTest {

    @TempDir
    Path dir;

    private MetadataNetworkFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @ParameterizedTest(name = "proposals {0}")
    @ValueSource(ints = {100, 300, 600})
    void networkMetadataProposeThroughput(int count) throws Exception {
        fixture = MetadataNetworkFixture.start(3, dir);
        fixture.awaitLeader();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long opsPerSecond = count * 1_000L / Math.max(1, elapsedMs);
        System.out.printf("PHASE25-BENCH META-PROPOSE %d -> %d ops/s%n",
                count, opsPerSecond);
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state().size())
                .isGreaterThanOrEqualTo(count);
    }

    @Test
    void leaderFailoverLatency() throws Exception {
        fixture = MetadataNetworkFixture.start(3, dir);
        fixture.awaitLeader();
        String firstLeader = fixture.leaderId();
        int leaderIndex = fixture.nodeIds.indexOf(firstLeader);
        long start = System.nanoTime();
        fixture.nodes.get(leaderIndex).close();
        fixture.endpoints.get(leaderIndex).close();
        String newLeader = fixture.awaitNewLeader(firstLeader);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE25-BENCH META-FAILOVER %d ms%n", elapsedMs);
        assertThat(newLeader).isNotEqualTo(firstLeader);
    }

    @Test
    void restartRecoveryLatency() throws Exception {
        fixture = MetadataNetworkFixture.start(1, dir);
        fixture.awaitLeader();
        for (int i = 0; i < 200; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        long start = System.nanoTime();
        fixture.restartNode(0);
        fixture.awaitLeader();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE25-BENCH META-RESTART %d ms%n", elapsedMs);
        assertThat(fixture.nodes.get(0).state().size())
                .isGreaterThanOrEqualTo(200);
    }

    @Test
    void snapshotRecoveryLatency() throws Exception {
        fixture = MetadataNetworkFixture.start(1, dir);
        fixture.awaitLeader();
        for (int i = 0; i < 1_100; i++) {
            fixture.client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        long start = System.nanoTime();
        fixture.restartNode(0);
        fixture.awaitLeader();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE25-BENCH META-SNAPSHOT-RESTART %d ms%n",
                elapsedMs);
        assertThat(fixture.nodes.get(0).state().size())
                .isGreaterThanOrEqualTo(1_100);
    }

    @ParameterizedTest(name = "writers {0}")
    @ValueSource(ints = {1, 4, 8})
    void concurrentProposalThroughput(int writers) throws Exception {
        fixture = MetadataNetworkFixture.start(3, dir);
        fixture.awaitLeader();
        int perWriter = 50;
        long start = System.nanoTime();
        List<Thread> workers = new java.util.ArrayList<>();
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
            worker.join(30_000);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long opsPerSecond = writers * perWriter * 1_000L
                / Math.max(1, elapsedMs);
        System.out.printf("PHASE25-BENCH META-CONCURRENT %d -> %d ops/s%n",
                writers, opsPerSecond);
        assertThat(fixture.nodes.get(fixture.leaderIndex()).state().size())
                .isGreaterThanOrEqualTo(writers * perWriter);
    }
}
