package io.tieringkv.txn.meta;

import io.tieringkv.cluster.rpc.MetaRaftRpc;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 元数据 Multi-Raft 网络化（ADR-0099 / TD-050）：TCP 提案、持久化、故障转移。 */
class MetadataNetworkRaftTest {

    private static final String GROUP = "txn-meta";

    @TempDir
    Path dir;

    private List<MultiRaftEndpoint> endpoints = new ArrayList<>();
    private List<TxnMetadataNode> nodes = new ArrayList<>();
    private MultiRaftEndpoint clientEndpoint;
    private TxnMetadataClient client;
    private List<String> nodeIds = new ArrayList<>();
    private Map<String, InetSocketAddress> addresses = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
        for (TxnMetadataNode node : nodes) {
            node.close();
        }
        for (MultiRaftEndpoint endpoint : endpoints) {
            endpoint.close();
        }
    }

    private void start(int count) throws Exception {
        nodes = new ArrayList<>();
        endpoints = new ArrayList<>();
        nodeIds = new ArrayList<>();
        addresses = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = "meta-" + i;
            nodeIds.add(id);
            addresses.put(id, new InetSocketAddress("127.0.0.1", freePort()));
        }
        Path dataRoot = dir.resolve("cluster-" + System.nanoTime());
        for (int i = 0; i < count; i++) {
            MultiRaftEndpoint endpoint = new MultiRaftEndpoint(
                    nodeIds.get(i), addresses.get(nodeIds.get(i)).getPort(),
                    addresses);
            TxnMetadataNode node = new TxnMetadataNode(nodeIds.get(i),
                    GROUP, endpoint, dataRoot);
            endpoints.add(endpoint);
            nodes.add(node);
            endpoint.start();
            node.start();
        }
        clientEndpoint = new MultiRaftEndpoint("client", freePort(),
                addresses);
        clientEndpoint.start();
        client = new TxnMetadataClient(clientEndpoint, GROUP, nodeIds);
    }

    private void awaitLeader() throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.leaderId();
                return;
            } catch (IllegalStateException ignored) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("no metadata leader over network");
    }

    @Test
    void electsSingleLeaderOverNetwork() throws Exception {
        start(3);
        String leaderId = client.leaderId();
        int leaders = 0;
        for (TxnMetadataNode node : nodes) {
            if (io.tieringkv.cluster.raft.RaftState.LEADER
                    .equals(node.raft().state())) {
                leaders++;
            }
        }
        assertThat(leaders).isEqualTo(1);
        assertThat(leaderId).isIn(nodeIds);
    }

    @Test
    void statusReportsLeaderAndTerm() throws Exception {
        start(3);
        String leaderId = client.leaderId();
        MetaRaftRpc.MetaRaftStatus status = clientEndpoint
                .callMetaStatus(leaderId, GROUP).join();
        assertThat(status.leaderId()).isEqualTo(leaderId);
        assertThat(status.term()).isGreaterThanOrEqualTo(0);
        assertThat(status.state()).isEqualTo("LEADER");
    }

    @Test
    void proposeAppliesOnAllNodes() throws Exception {
        start(3);
        awaitLeader();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        Thread.sleep(200);
        for (TxnMetadataNode node : nodes) {
            assertThat(node.state().get("t1")).isNotNull();
        }
    }

    @Test
    void commitDecisionAppliedEverywhere() throws Exception {
        start(3);
        awaitLeader();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.prepare("t1", 9))).join();
        long index = client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.commit("t1", 9))).join();
        assertThat(index).isGreaterThanOrEqualTo(0);
        Thread.sleep(200);
        for (TxnMetadataNode node : nodes) {
            TxnMetaEntry entry = node.state().get("t1");
            assertThat(entry.state()).isEqualTo(TxnMetaEntry.State.COMMITTED);
            assertThat(entry.decisionIndex()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void followerRejectsProposeWithRedirectError() throws Exception {
        start(3);
        String leaderId = client.leaderId();
        String follower = nodeIds.stream().filter(id -> !id.equals(leaderId))
                .findFirst().orElseThrow();
        byte[] command = TxnMetaCodec.encode(TxnMetaCommand.register(
                "t-redirect", new byte[]{1}, 1, Map.of("r1", List.of())));
        assertThatThrownBy(() -> clientEndpoint.callPropose(
                follower, GROUP, command).join())
                .hasRootCauseInstanceOf(
                        MetaRaftRpc.NotLeaderException.class);
    }

    @Test
    void leaderKillFailoverProposes() throws Exception {
        start(3);
        String firstLeader = client.leaderId();
        int leaderIndex = nodeIds.indexOf(firstLeader);
        nodes.get(leaderIndex).close();
        endpoints.get(leaderIndex).close();
        String newLeader = awaitNewLeader(firstLeader);
        assertThat(newLeader).isNotEqualTo(firstLeader);
        client.proposer().apply(TxnMetaCodec.encode(TxnMetaCommand.register(
                "t2", new byte[]{2}, 2, Map.of("r1", List.of())))).join();
        Thread.sleep(200);
        int applied = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (i != leaderIndex && nodes.get(i).state().get("t2") != null) {
                applied++;
            }
        }
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    @Test
    void restartPreservesCommittedDecision() throws Exception {
        start(1);
        awaitLeader();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.prepare("t1", 9))).join();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.commit("t1", 9))).join();
        Path dataRoot = nodes.get(0).stateDir();
        int port = endpoints.get(0).boundPort();
        nodes.get(0).close();
        endpoints.get(0).close();
        Thread.sleep(1_000);
        Map<String, InetSocketAddress> single = Map.of(
                "meta-0", new InetSocketAddress("127.0.0.1", port));
        MultiRaftEndpoint restartedEndpoint = new MultiRaftEndpoint(
                "meta-0", port, single);
        TxnMetadataNode restarted = new TxnMetadataNode("meta-0", GROUP,
                restartedEndpoint, dataRoot);
        restartedEndpoint.start();
        restarted.start();
        nodes.set(0, restarted);
        endpoints.set(0, restartedEndpoint);
        awaitLeader();
        assertThat(restarted.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.COMMITTED);
    }

    @Test
    void followerPartitionThenCatchUp() throws Exception {
        start(3);
        String leaderId = client.leaderId();
        int followerIndex = nodeIds.stream()
                .map(id -> nodeIds.indexOf(id))
                .filter(i -> !nodeIds.get(i).equals(leaderId))
                .findFirst().orElseThrow();
        int port = endpoints.get(followerIndex).boundPort();
        nodes.get(followerIndex).close();
        endpoints.get(followerIndex).close();
        client.proposer().apply(TxnMetaCodec.encode(
                TxnMetaCommand.register("t1", new byte[]{1}, 1,
                        Map.of("r1", List.of())))).join();
        Thread.sleep(100);
        int applied = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (i != followerIndex && nodes.get(i).state().get("t1") != null) {
                applied++;
            }
        }
        assertThat(applied).isGreaterThanOrEqualTo(2);
        Thread.sleep(1_000);
        Path dataRoot = nodes.get(followerIndex).stateDir();
        MultiRaftEndpoint restartedEndpoint = new MultiRaftEndpoint(
                nodeIds.get(followerIndex), port, addresses);
        TxnMetadataNode restarted = new TxnMetadataNode(
                nodeIds.get(followerIndex), GROUP, restartedEndpoint,
                dataRoot);
        restartedEndpoint.start();
        restarted.start();
        nodes.set(followerIndex, restarted);
        endpoints.set(followerIndex, restartedEndpoint);
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline
                && restarted.state().get("t1") == null) {
            Thread.sleep(50);
        }
        assertThat(restarted.state().get("t1")).isNotNull();
    }

    @Test
    void snapshotCompactionSurvivesRestart() throws Exception {
        start(1);
        awaitLeader();
        int count = 1_100; // 超过 RaftNode 快照阈值 1024
        for (int i = 0; i < count; i++) {
            client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        Path dataRoot = nodes.get(0).stateDir();
        int port = endpoints.get(0).boundPort();
        nodes.get(0).close();
        endpoints.get(0).close();
        Thread.sleep(1_000);
        Map<String, InetSocketAddress> single = Map.of(
                "meta-0", new InetSocketAddress("127.0.0.1", port));
        MultiRaftEndpoint restartedEndpoint = new MultiRaftEndpoint(
                "meta-0", port, single);
        TxnMetadataNode restarted = new TxnMetadataNode("meta-0", GROUP,
                restartedEndpoint, dataRoot);
        restartedEndpoint.start();
        restarted.start();
        nodes.set(0, restarted);
        endpoints.set(0, restartedEndpoint);
        awaitLeader();
        assertThat(restarted.state().size()).isGreaterThanOrEqualTo(count);
        assertThat(restarted.state().get("t" + (count - 1))).isNotNull();
    }

    @Test
    void concurrentNetworkProposals() throws Exception {
        start(3);
        awaitLeader();
        int threads = 4;
        int perThread = 10;
        AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    try {
                        client.proposer().apply(TxnMetaCodec.encode(
                                TxnMetaCommand.register(
                                        "c" + (base + i), new byte[]{1},
                                        base + i, Map.of("r1", List.of()))))
                                .join();
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        assertThat(failures.get()).isZero();
        Thread.sleep(300);
        int applied = 0;
        for (TxnMetadataNode node : nodes) {
            applied = Math.max(applied, node.state().size());
        }
        assertThat(applied).isGreaterThanOrEqualTo(threads * perThread);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedNodeCount(int count) throws Exception {
        start(count);
        awaitLeader();
        client.proposer().apply(TxnMetaCodec.encode(TxnMetaCommand.register(
                "t1", new byte[]{1}, 1, Map.of("r1", List.of())))).join();
        Thread.sleep(150);
        int applied = 0;
        for (TxnMetadataNode node : nodes) {
            if (node.state().get("t1") != null) {
                applied++;
            }
        }
        assertThat(applied).isGreaterThanOrEqualTo(count / 2 + 1);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedProposals(int txnCount) throws Exception {
        start(3);
        awaitLeader();
        for (int i = 0; i < txnCount; i++) {
            client.proposer().apply(TxnMetaCodec.encode(
                    TxnMetaCommand.register("t" + i, new byte[]{1}, i,
                            Map.of("r1", List.of())))).join();
        }
        Thread.sleep(200);
        assertThat(clientLeaderState().size())
                .isGreaterThanOrEqualTo(txnCount);
    }

    private String awaitNewLeader(String previous) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String candidate = client.leaderId();
                if (!candidate.equals(previous)) {
                    return candidate;
                }
            } catch (IllegalStateException ignored) {
                // 选举中
            }
            Thread.sleep(20);
        }
        throw new AssertionError("failover did not complete");
    }

    private TransactionMetadataState clientLeaderState() {
        String leaderId = client.leaderId();
        int index = nodeIds.indexOf(leaderId);
        return nodes.get(index).state();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
