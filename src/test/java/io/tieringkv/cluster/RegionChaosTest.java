package io.tieringkv.cluster;

import io.tieringkv.cluster.lifecycle.LeaderTransferManager;
import io.tieringkv.cluster.lifecycle.merge.MergeController;
import io.tieringkv.cluster.lifecycle.split.RegionSplitTask;
import io.tieringkv.cluster.lifecycle.split.SplitController;
import io.tieringkv.cluster.migration.parallel.RegionTransferManager;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.cluster.region.StaleRegionEpochException;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Region 生命周期混沌（Phase 17）：split/merge/transfer 与故障组合。 */
class RegionChaosTest {

    @TempDir
    Path dir;

    @Test
    void splitDuringTenThousandWritesNoLoss() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        SplitController controller = new SplitController(regions);
        try {
            for (int i = 0; i < 5_000; i++) {
                source.put(key(i), bytes("v"));
            }
            RegionSplitTask task = controller.beginSplit(
                    new RegionId(1), key(5_000), source, left, right);
            controller.snapshot(task);
            controller.install(task);
            AtomicBoolean done = new AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                for (int i = 5_000; i < 10_000; i++) {
                    controller.bufferWrite(new RegionId(1),
                            key(i), bytes("v"), i, -1);
                }
                done.set(true);
            });
            writer.start();
            writer.join(20_000);
            assertThat(done.get()).isTrue();
            controller.commit(task);
            controller.cleanup(task);
            assertThat(left.size() + right.size()).isEqualTo(10_000);
            for (int i = 0; i < 10_000; i++) {
                assertThat(left.get(key(i)) == null ? right.get(key(i)) != null
                        : true).isTrue();
            }
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    @Test
    void splitThenParallelMigrateChildren() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        SplitController controller = new SplitController(regions);
        try {
            for (int i = 0; i < 10_000; i++) {
                source.put(key(i), bytes("v"));
            }
            controller.split(new RegionId(1), key(5_000),
                    source, left, right);
            MemTable targetA = MemTable.create();
            MemTable targetB = MemTable.create();
            try {
                new RegionTransferManager(left, targetA,
                        dir.resolve("a"), 4, Long.MAX_VALUE).migrate(4);
                new RegionTransferManager(right, targetB,
                        dir.resolve("b"), 4, Long.MAX_VALUE).migrate(4);
                assertThat(targetA.size() + targetB.size()).isEqualTo(10_000);
            } finally {
                targetA.close();
                targetB.close();
            }
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    @Test
    void mergeAfterStorageFailureRecovers() throws Exception {
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("k0050"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("k0050"), bytes("z"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        MergeController controller = new MergeController(regions);
        try {
            for (int i = 0; i < 50; i++) {
                left.put(key(i), bytes("v"));
                right.put(key(i + 50), bytes("v"));
            }
            // "leader kill"：右存储不可用 → 合并失败
            FailingStorage dead = new FailingStorage();
            assertThatThrownBy(() -> controller.merge(
                    new RegionId(1), new RegionId(2), left, dead))
                    .isInstanceOf(Throwable.class);
            // 恢复：重置生命周期状态 + 新控制器重试
            regions.markState(new RegionId(1), RegionState.NORMAL);
            regions.markState(new RegionId(2), RegionState.NORMAL);
            MergeController retryController = new MergeController(regions);
            // 重启恢复（新存储装载原数据）→ 合并成功
            MemTable recovered = MemTable.create();
            for (int i = 50; i < 100; i++) {
                recovered.put(key(i), bytes("v"));
            }
            Region merged = retryController.merge(
                    new RegionId(1), new RegionId(2), left, recovered);
            assertThat(merged.regionId().id()).isEqualTo(13);
            assertThat(left.size()).isEqualTo(100);
            recovered.close();
        } finally {
            left.close();
            right.close();
        }
    }

    @Test
    void transferUnder200msLatencyAndLoss() throws Exception {
        LatencyLossFixture fixture = LatencyLossFixture.start(200, 5);
        try {
            proposeOnCurrentLeader(fixture, bytes("committed"));
            // 200ms 延迟 + 5% 丢包下，单次 TimeoutNow 可能被丢弃或 matchIndex
            // 滞后于日志长度，transferLeadership 返回 false 是合法结果；
            // 不变量是“最终成功”，因此重试并在每次重试前重新解析 leader/target。
            boolean transferred = false;
            String lastTarget = null;
            long successElapsedMs = -1;
            for (int attempt = 0; attempt < 6 && !transferred; attempt++) {
                try {
                    RaftNode leader = fixture.leader();
                    RaftNode target = fixture.followerOf(leader.id());
                    lastTarget = target.id();
                    awaitTrue("caught up", () ->
                            target.logSize() == leader.logSize(), 15_000);
                    long start = System.nanoTime();
                    transferred = leader.transferLeadership(target.id())
                            .get(15, TimeUnit.SECONDS);
                    if (transferred) {
                        successElapsedMs = (System.nanoTime() - start) / 1_000_000;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception | AssertionError attemptFailed) {
                    // 高负载下选举解析/追平/交接可能超过单次窗口：
                    // 视为该次尝试失败，重新解析 leader/target 后重试
                    transferred = false;
                }
                if (!transferred) {
                    Thread.sleep(100);
                }
            }
            assertThat(transferred).as("transfer eventually succeeds").isTrue();
            assertThat(lastTarget).isNotNull();
            String expectedTarget = lastTarget;
            awaitTrue("new leader elected", () ->
                    fixture.nodes.stream().anyMatch(node ->
                            node.id().equals(expectedTarget)
                                    && node.state() == RaftState.LEADER), 15_000);
            // 成功的那一次交接本身必须在 5s 内完成（不含重试等待）
            assertThat(successElapsedMs).isLessThan(5000);
            assertThat(fixture.applied()).contains("committed");
        } finally {
            fixture.close();
        }
    }

    @Test
    void transferDuringPartitionFailsGracefully() throws Exception {
        LatencyLossFixture fixture = LatencyLossFixture.start(0, 0);
        try {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            fixture.transport.partition(target.id());
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isFalse();
            fixture.transport.heal();
            awaitTrue("caught up", () ->
                    target.logSize() == leader.logSize(), 10_000);
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            fixture.close();
        }
    }

    @Test
    void regionAChaosRegionBUnaffected() throws Exception {
        // 两个独立 raft 组：A 注入延迟/丢包，B 不受影响
        LatencyLossFixture groupA = LatencyLossFixture.start(50, 2);
        RaftFixturePlain groupB = RaftFixturePlain.start();
        try {
            RaftNode bLeader = groupB.leader();
            bLeader.propose(bytes("b-ok")).get(5, TimeUnit.SECONDS);
            assertThat(bLeader.commitIndex()).isZero();
            assertThat(groupA.applied()).isEmpty();
            proposeOnCurrentLeader(groupA, bytes("a-slow"));
            assertThat(groupA.applied()).contains("a-slow");
        } finally {
            groupA.close();
            groupB.close();
        }
    }

    @Test
    void staleEpochRejectedAfterSplitDuringChaos() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        SplitController controller = new SplitController(regions);
        try {
            source.put(bytes("k1"), bytes("v"));
            RegionEpoch stale = regions.get(new RegionId(1)).epoch();
            controller.split(new RegionId(1), bytes("m"),
                    source, left, right);
            assertThatThrownBy(() -> regions.routeStrict(bytes("k1"), stale))
                    .isInstanceOf(StaleRegionEpochException.class);
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    @Test
    void splitMergeCycleDataIntact() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        MemTable merged = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("k0050"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("k0050"), bytes("z"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        try {
            for (int i = 0; i < 100; i++) {
                source.put(key(i), bytes("v"));
            }
            SplitController splitter = new SplitController(regions);
            splitter.split(new RegionId(1), key(50), source, left, right);
            MergeController merger = new MergeController(regions);
            Region mergedRegion = merger.merge(
                    new RegionId(11), new RegionId(12), left, right);
            // 数据从 left/right 汇总（merge 右→左），验证完整
            assertThat(mergedRegion.regionId().id()).isEqualTo(113);
            assertThat(left.size()).isEqualTo(100);
            merged.close();
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    @Test
    void leaderTransferManagerUnderLatencyUpdatesEpoch() throws Exception {
        LatencyLossFixture fixture = LatencyLossFixture.start(50, 0);
        try {
            RegionManager regions = new RegionManager();
            boolean transferred = false;
            String lastTarget = null;
            for (int attempt = 0; attempt < 5 && !transferred; attempt++) {
                RaftNode leader = fixture.leader();
                RaftNode target = fixture.followerOf(leader.id());
                lastTarget = target.id();
                if (regions.get(new RegionId(1)) == null) {
                    regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                            List.of("n1", "n2", "n3"), RegionEpoch.INITIAL,
                            leader.id());
                }
                awaitTrue("caught up", () ->
                        target.logSize() == leader.logSize(), 10_000);
                LeaderTransferManager manager = new LeaderTransferManager(
                        regions, Map.of(new RegionId(1), leader));
                transferred = manager.transferLeader(
                        new RegionId(1), target.id());
                if (!transferred) {
                    Thread.sleep(100);
                }
            }
            assertThat(transferred).isTrue();
            assertThat(regions.get(new RegionId(1)).leader())
                    .isEqualTo(lastTarget);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitConcurrentReadsConsistent() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        SplitController controller = new SplitController(regions);
        try {
            for (int i = 0; i < 2_000; i++) {
                source.put(key(i), bytes("v"));
            }
            RegionSplitTask task = controller.beginSplit(
                    new RegionId(1), key(1_000), source, left, right);
            controller.snapshot(task);
            // 分裂窗口内读取源（生产路由仍指向父 region）
            assertThat(source.get(key(500))).isNotNull();
            controller.install(task);
            controller.commit(task);
            controller.cleanup(task);
            assertThat(left.get(key(500))).isNotNull();
            assertThat(right.get(key(1_500))).isNotNull();
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    private static byte[] key(int i) {
        return ("ck:" + String.format("%05d", i)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void proposeOnCurrentLeader(
            LatencyLossFixture fixture, byte[] command) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            RaftNode leader = fixture.leader();
            try {
                leader.propose(command).get(20, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                Thread.sleep(50); // leader 可能已变更，重新解析
            }
        }
        throw new AssertionError("propose failed across leader changes");
    }

    /** 带延迟/丢包/分区的本地传输（Phase 17 混沌）。 */
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
                                              java.util.function.Supplier<T> supplier) {
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

    private static final class LatencyLossFixture implements AutoCloseable {
        private final List<RaftNode> nodes;
        private final List<String> applied;
        private final ChaosTransport transport;

        private LatencyLossFixture(List<RaftNode> nodes, List<String> applied,
                                   ChaosTransport transport) {
            this.nodes = nodes;
            this.applied = applied;
            this.transport = transport;
        }

        private static LatencyLossFixture start(long latency, int loss)
                throws InterruptedException {
            List<String> applied = Collections.synchronizedList(new ArrayList<>());
            List<RaftNode> peers = new ArrayList<>();
            List<RaftNode> nodes = new ArrayList<>();
            Map<String, ChaosTransport> transports = new java.util.HashMap<>();
            LeaderElection election = latency > 100
                    ? new LeaderElection(400, 300)
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
            RaftNode leader = awaitLeader(nodes, 15_000);
            return new LatencyLossFixture(
                    nodes, applied, transports.get(leader.id()));
        }

        private RaftNode leader() throws InterruptedException {
            return awaitLeader(nodes, 15_000);
        }

        private RaftNode followerOf(String leaderId) {
            for (RaftNode node : nodes) {
                if (!node.id().equals(leaderId)) {
                    return node;
                }
            }
            throw new IllegalStateException("no follower");
        }

        private List<String> applied() {
            return applied;
        }

        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }

    private static final class RaftFixturePlain implements AutoCloseable {
        private final List<RaftNode> nodes;
        private final List<String> applied;

        private RaftFixturePlain(List<RaftNode> nodes, List<String> applied) {
            this.nodes = nodes;
            this.applied = applied;
        }

        private static RaftFixturePlain start() throws InterruptedException {
            List<String> applied = Collections.synchronizedList(new ArrayList<>());
            List<RaftNode> peers = new ArrayList<>();
            List<RaftNode> nodes = new ArrayList<>();
            for (String id : List.of("n1", "n2", "n3")) {
                RaftNode node = new RaftNode(id, new ChaosTransport(peers, id, 0, 0),
                        (index, command) -> applied.add(
                                new String(command, StandardCharsets.UTF_8)),
                        ELECTION, 25, 10, new MemoryRaftLog(), null, null);
                nodes.add(node);
            }
            peers.addAll(nodes);
            RaftTestSupport.startAll(nodes.toArray(new RaftNode[0]));
            awaitLeader(nodes, 5000);
            return new RaftFixturePlain(nodes, applied);
        }

        private RaftNode leader() throws InterruptedException {
            return awaitLeader(nodes, 5000);
        }

        private List<String> applied() {
            return applied;
        }

        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }

    /** 始终失败的存储（模拟节点宕机）。 */
    private static final class FailingStorage implements io.tieringkv.storage.StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
            throw new IllegalStateException("node down");
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            throw new IllegalStateException("node down");
        }

        @Override
        public byte[] get(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean delete(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean exists(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            throw new IllegalStateException("node down");
        }

        @Override
        public long size() {
            throw new IllegalStateException("node down");
        }
    }
}
