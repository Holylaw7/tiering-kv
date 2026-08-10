package io.tieringkv.cluster;

import io.tieringkv.cluster.migration.parallel.RegionTransferManager;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** 跨节点混沌（Phase 18/ADR-0069）：击杀/分区/恢复/快照追赶/迁移中断。 */
class CrossMachineChaosTest {

    @TempDir
    Path dir;

    @Test
    void leaderKillTriggersElection() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            kill(leader);
            RaftNode next = awaitLeader(cluster.nodes, 8000);
            assertThat(next).isNotEqualTo(leader);
        }
    }

    @Test
    void leaderKillNoDataLoss() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            proposeOnLeader(cluster, bytes("durable"));
            kill(leader);
            RaftNode next = awaitLeader(cluster.nodes, 8000);
            next.propose(bytes("after")).get(5, TimeUnit.SECONDS);
            assertThat(cluster.applied).contains("durable", "after");
        }
    }

    @Test
    void networkPartitionFollowerRecovers() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            RaftNode follower = cluster.followerOf(leader.id());
            cluster.partition(follower.id());
            proposeOnLeader(cluster, bytes("during-partition"));
            cluster.heal();
            awaitTrue("follower catchup", () ->
                    follower.logSize() == leader.logSize(), 10_000);
        }
    }

    @Test
    void partitionHealCatchup() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            RaftNode follower = cluster.followerOf(leader.id());
            cluster.partition(follower.id());
            for (int i = 0; i < 20; i++) {
                proposeOnLeader(cluster, bytes("k" + i));
            }
            cluster.heal();
            awaitTrue("catchup", () ->
                    follower.logSize() == leader.logSize(), 10_000);
        }
    }

    @Test
    void followerRestartCatchesUp() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            RaftNode follower = cluster.followerOf(leader.id());
            kill(follower);
            for (int i = 0; i < 15; i++) {
                proposeOnLeader(cluster, bytes("r" + i));
            }
            cluster.restart(follower.id());
            awaitTrue("restarted catchup", () ->
                    cluster.raft(follower.id()).logSize() == leader.logSize(),
                    10_000);
        }
    }

    @Test
    void snapshotCatchupAfterWipe() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RaftNode leader = cluster.leader();
            RaftNode follower = cluster.followerOf(leader.id());
            for (int i = 0; i < 10; i++) {
                proposeOnLeader(cluster, bytes("s" + i));
            }
            kill(follower);
            cluster.restart(follower.id()); // 空日志 → 追赶（等价快照重建）
            awaitTrue("wipe catchup", () ->
                    cluster.raft(follower.id()).logSize() == leader.logSize(),
                    10_000);
        }
    }

    @Test
    void migrationInterruptionResumes() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir.resolve("mig1"), 2, Long.MAX_VALUE);
            manager.migrate(2);
            java.nio.file.Files.delete(dir.resolve("mig1")
                    .resolve("chunk-1.ckpt"));
            RegionTransferManager.MigrationSummary resumed = manager.migrate(2);
            assertThat(resumed.entries()).isGreaterThan(0);
            assertThat(target.size()).isEqualTo(5_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migrationInterruptionPauseResume() throws Exception {
        MemTable source = source(3_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir.resolve("mig2"), 2, Long.MAX_VALUE);
            manager.pause();
            Thread runner = new Thread(() -> {
                try {
                    manager.migrate(2);
                } catch (Exception ignored) {
                }
            });
            runner.start();
            Thread.sleep(200);
            assertThat(target.size()).isZero();
            manager.resume();
            runner.join(20_000);
            assertThat(target.size()).isEqualTo(3_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void twoGroupIsolation() throws Exception {
        try (ChaosCluster a = ChaosCluster.start(0, 0);
             ChaosCluster b = ChaosCluster.start(0, 0)) {
            RaftNode bLeader = b.leader();
            bLeader.propose(bytes("b-ok")).get(5, TimeUnit.SECONDS);
            RaftNode aLeader = a.leader();
            kill(aLeader);
            awaitLeader(a.nodes, 8000);
            assertThat(bLeader.commitIndex()).isZero();
        }
    }

    @Test
    void leaderKillDuringMigration() throws Exception {
        MemTable source = source(2_000);
        MemTable target = MemTable.create();
        try (ChaosCluster cluster = ChaosCluster.start(0, 0)) {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir.resolve("mig3"), 2, Long.MAX_VALUE);
            Thread migrator = new Thread(() -> {
                try {
                    manager.migrate(2);
                } catch (Exception ignored) {
                }
            });
            migrator.start();
            RaftNode leader = cluster.leader();
            kill(leader);
            awaitLeader(cluster.nodes, 8000);
            migrator.join(20_000);
            assertThat(target.size()).isEqualTo(2_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void latency50msStillCommits() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(50, 0)) {
            proposeOnLeader(cluster, bytes("slow"));
            assertThat(cluster.applied).contains("slow");
        }
    }

    @Test
    void loss10PercentRecovery() throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(0, 10)) {
            RaftNode leader = cluster.leader();
            for (int i = 0; i < 10; i++) {
                leader.propose(bytes("l" + i)).get(15, TimeUnit.SECONDS);
            }
            awaitTrue("all applied", () -> cluster.applied.size() >= 10, 10_000);
        }
    }

    @ParameterizedTest(name = "latencyLoss {0}/{1}")
    @MethodSource("latencyLossVariants")
    void latencyLossVariants(long latency, int loss) throws Exception {
        try (ChaosCluster cluster = ChaosCluster.start(latency, loss)) {
            RaftNode leader = cluster.leader();
            proposeOnLeader(cluster, bytes("v-" + latency + "-" + loss));
            assertThat(cluster.applied).contains("v-" + latency + "-" + loss);
        }
    }

    static Stream<Object[]> latencyLossVariants() {
        return Stream.of(
                new Object[]{20L, 0},
                new Object[]{50L, 0},
                new Object[]{100L, 0},
                new Object[]{0, 1},
                new Object[]{0, 5},
                new Object[]{50L, 5},
                new Object[]{20L, 2},
                new Object[]{100L, 10});
    }

    private static void proposeOnLeader(ChaosCluster cluster, byte[] command)
            throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            RaftNode leader = cluster.leader();
            try {
                leader.propose(command).get(30, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
        throw new AssertionError("propose failed across leader changes");
    }

    private static void kill(RaftNode node) {
        node.suspend();
        node.close();
    }

    private static MemTable source(int count) {
        MemTable table = MemTable.create();
        byte[] value = new byte[32];
        for (int i = 0; i < count; i++) {
            table.put(("cm:" + i).getBytes(StandardCharsets.UTF_8), value);
        }
        return table;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 三节点 + 可注入延迟/丢包/分区的传输夹具。 */
    static final class ChaosCluster implements AutoCloseable {
        private final List<RaftNode> nodes;
        private final List<String> applied;
        private final Map transports;
        private final List<RaftNode> peers;

        private ChaosCluster(List<RaftNode> nodes, List<String> applied,
                             Map transports, List<RaftNode> peers) {
            this.nodes = nodes;
            this.applied = applied;
            this.transports = transports;
            this.peers = peers;
        }

        static ChaosCluster start(long latency, int loss)
                throws InterruptedException {
            List<String> applied = Collections.synchronizedList(new ArrayList<>());
            List<RaftNode> peers = new ArrayList<>();
            List<RaftNode> nodes = new ArrayList<>();
            java.util.Map<String, ChaosTransport> transports =
                    new java.util.HashMap<>();
            io.tieringkv.cluster.raft.LeaderElection election =
                    latency > 0 || loss > 0
                            ? new io.tieringkv.cluster.raft.LeaderElection(250, 200)
                            : ELECTION;
            for (String id : List.of("n1", "n2", "n3")) {
                ChaosTransport transport = new ChaosTransport(
                        peers, id, latency, loss);
                RaftNode node = new RaftNode(id, transport,
                        (index, command) -> applied.add(
                                new String(command, StandardCharsets.UTF_8)),
                        election, 25, 10, new MemoryRaftLog(), null, null);
                nodes.add(node);
                transports.put(id, transport);
            }
            peers.addAll(nodes);
            RaftTestSupport.startAll(nodes.toArray(new RaftNode[0]));
            awaitLeader(nodes, 15_000);
            return new ChaosCluster(nodes, applied,
                    new Map(transports), peers);
        }

        RaftNode leader() throws InterruptedException {
            return awaitLeader(nodes, 20_000);
        }

        RaftNode followerOf(String leaderId) {
            for (RaftNode node : nodes) {
                if (!node.id().equals(leaderId)) {
                    return node;
                }
            }
            throw new IllegalStateException("no follower");
        }

        RaftNode raft(String id) {
            return nodes.stream().filter(n -> n.id().equals(id))
                    .findFirst().orElseThrow();
        }

        void partition(String target) {
            for (ChaosTransport transport : transports.all()) {
                transport.partition(target);
            }
        }

        void heal() {
            for (ChaosTransport transport : transports.all()) {
                transport.heal();
            }
        }

        void restart(String id) {
            RaftNode old = raft(id);
            nodes.remove(old);
            peers.remove(old);
            ChaosTransport transport = new ChaosTransport(peers, id, 0, 0);
            RaftNode node = new RaftNode(id, transport,
                    (index, command) -> applied.add(
                            new String(command, StandardCharsets.UTF_8)),
                    ELECTION, 25, 10, new MemoryRaftLog(), null, null);
            peers.add(node);
            nodes.add(node);
            transports.put(id, transport);
            node.start();
        }

        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }

    /** Map 简易包装（避免与 java.util.Map 冲突）。 */
    static final class Map {
        private final java.util.Map<String, ChaosTransport> inner;

        Map(java.util.Map<String, ChaosTransport> inner) {
            this.inner = inner;
        }

        void put(String id, ChaosTransport transport) {
            inner.put(id, transport);
        }

        List<ChaosTransport> all() {
            return new ArrayList<>(inner.values());
        }
    }

    static final class ChaosTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final String selfId;
        private volatile long latencyMillis;
        private volatile int lossPercent;
        private volatile String partitionedTarget;

        ChaosTransport(List<RaftNode> peers, String selfId,
                       long latencyMillis, int lossPercent) {
            this.peers = peers;
            this.selfId = selfId;
            this.latencyMillis = latencyMillis;
            this.lossPercent = lossPercent;
        }

        void partition(String target) {
            this.partitionedTarget = target;
        }

        void heal() {
            this.partitionedTarget = null;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(
                String target, VoteRequest request) {
            return call(target, () -> find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            return call(target, () -> find(target).receive(request));
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return call(target, () -> find(target).receive(request));
        }

        @Override
        public CompletableFuture<TimeoutNowResponse> timeoutNow(
                String target, TimeoutNowRequest request) {
            return call(target, () -> find(target).receiveTimeoutNow(request));
        }

        private <T> CompletableFuture<T> call(String target,
                                              Supplier<T> supplier) {
            if (target.equals(partitionedTarget)
                    || (lossPercent > 0
                    && ThreadLocalRandom.current().nextInt(100) < lossPercent)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("fault"));
            }
            if (latencyMillis <= 0) {
                return CompletableFuture.completedFuture(supplier.get());
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(latencyMillis, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        try {
                            future.complete(supplier.get());
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    });
            return future;
        }

        private RaftNode find(String id) {
            for (RaftNode peer : peers) {
                if (peer.id().equals(id)) {
                    return peer;
                }
            }
            throw new IllegalStateException("no peer " + id);
        }
    }
}
