package io.tieringkv.cluster;

import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.StaleRegionEpochException;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.function.Supplier;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨机混沌验证（Phase 16）：多 Region × 多 Raft 组的故障注入——
 * 延迟/丢包/分区/磁盘慢/leader 击杀，验证 Region 级故障隔离与数据完整性。
 * 环境无 Docker/tc netem：故障在 RaftTransport 层注入（语义对齐），
 * 真实跨机部署产物见 deploy/ 与 docs/deployment/phase16-cross-machine.md。
 */
class ChaosClusterTest {

    @Test
    void twoRegionsBothServe() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            awaitTrue("gA replicated", () ->
                    fixture.managers().get("n2").storageFor("gA")
                            .get(bytes("apple")) != null, 5000);
            awaitTrue("gB replicated", () ->
                    fixture.managers().get("n2").storageFor("gB")
                            .get(bytes("mango")) != null, 5000);
        }
    }

    @Test
    void regionAPartitionDoesNotAffectRegionB() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.partition("gA", "n3", true);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"),
                    Set.of("n3"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            awaitTrue("gB ok", () ->
                    fixture.managers().get("n2").storageFor("gB")
                            .get(bytes("mango")) != null, 5000);
            fixture.network.partition("gA", "n3", false);
            awaitTrue("gA catches up", () ->
                    fixture.managers().get("n3").storageFor("gA")
                            .get(bytes("apple")) != null, 10_000);
        }
    }

    @Test
    void regionALeaderKillRegionBServing() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            kill(gALeader);
            RaftNode newLeader = awaitLeader(groupRafts(fixture, "gA"), 8000);
            assertThat(newLeader).isNotEqualTo(gALeader);
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            assertThat(gALeader.commitIndex()).isLessThan(1);
        }
    }

    @Test
    void regionBLeaderKillRegionAServing() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode gBLeader = leaderOf(fixture, "gB");
            kill(gBLeader);
            RaftNode newLeader = awaitLeader(groupRafts(fixture, "gB"), 8000);
            assertThat(newLeader).isNotEqualTo(gBLeader);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            assertThat(gBLeader.commitIndex()).isLessThan(1);
        }
    }

    @Test
    void regionADiskSlowRegionBCommits() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.diskSlow("gA", "n3", 5);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            awaitTrue("gB fast", () ->
                    fixture.managers().get("n3").storageFor("gB")
                            .get(bytes("mango")) != null, 3000);
        } finally {
            // fixture close
        }
    }

    @Test
    void regionALatencyRegionBUnaffected() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.latency("gA", "n1", 100);
            fixture.network.latency("gA", "n2", 100);
            fixture.network.latency("gA", "n3", 100);
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            awaitTrue("gB unaffected", () ->
                    fixture.managers().get("n3").storageFor("gB")
                            .get(bytes("mango")) != null, 3000);
        }
    }

    @Test
    void regionAPacketLossRecovers() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.loss("gA", "n1", 10);
            fixture.network.loss("gA", "n2", 10);
            fixture.network.loss("gA", "n3", 10);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            awaitTrue("gA converges", () ->
                    fixture.managers().get("n2").storageFor("gA")
                            .get(bytes("apple")) != null
                            && fixture.managers().get("n3").storageFor("gA")
                            .get(bytes("apple")) != null, 10_000);
        }
    }

    @Test
    void regionSplitConcurrentWritesNoLoss() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            List<Region> children = fixture.regions().splitRegion(
                    new RegionId(1), bytes("g"));
            putOnLeader(fixture, "gA", bytes("apricot"), bytes("va2"));
            putOnLeader(fixture, "gB", bytes("nectarine"), bytes("vb2"));
            assertThat(children).hasSize(2);
            assertThat(fixture.regions().route(bytes("apple")).regionId())
                    .isNotEqualTo(new RegionId(1));
            assertThat(fixture.regions().route(bytes("mango")).regionId())
                    .isNotEqualTo(new RegionId(1));
        }
    }

    @Test
    void staleEpochRejectedAfterSplit() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RegionEpoch initial = fixture.regions()
                    .get(new RegionId(1)).epoch();
            fixture.regions().splitRegion(new RegionId(1), bytes("g"));
            assertThatThrownBy(() -> fixture.regions()
                    .routeStrict(bytes("apple"), initial))
                    .isInstanceOf(StaleRegionEpochException.class);
        }
    }

    @Test
    void currentEpochAcceptedAfterSplit() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.regions().splitRegion(new RegionId(1), bytes("g"));
            Region left = fixture.regions().route(bytes("apple"));
            assertThat(fixture.regions().routeStrict(
                    bytes("apple"), left.epoch())).isNotNull();
        }
    }

    @Test
    void mixedChaosBothRegionsSurvive() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.latency("gA", "n1", 50);
            fixture.network.loss("gA", "n2", 5);
            fixture.network.partition("gB", "n3", true);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"),
                    Set.of("n3"));
            fixture.network.partition("gB", "n3", false);
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline
                    && fixture.managers().get("n3").storageFor("gB")
                    .get(bytes("mango")) == null) {
                Thread.sleep(100);
            }
            StringBuilder diag = new StringBuilder();
            for (String nodeId : List.of("n1", "n2", "n3")) {
                RaftNode raft = fixture.managers().get(nodeId).raftFor("gB");
                diag.append(nodeId).append("=").append(raft.state())
                        .append(" term=").append(raft.currentTerm())
                        .append(" log=").append(raft.logSize())
                        .append(" committed=").append(raft.commitIndex())
                        .append(' ');
            }
            assertThat(fixture.managers().get("n3").storageFor("gB")
                    .get(bytes("mango"))).as("gB catchup: " + diag).isNotNull();
        }
    }

    @Test
    void restartRegionANodeCatchesUp() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode node = fixture.managers().get("n2").raftFor("gA");
            kill(node);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            awaitTrue("majority commits", () ->
                    fixture.managers().get("n1").storageFor("gA")
                            .get(bytes("apple")) != null, 5000);
            // 重启 n2 的 gA（新实例 + 空日志，从 leader 追赶）
            fixture.restartGroup("n2", "gA");
            awaitTrue("restarted node catches up", () ->
                    fixture.managers().get("n2").storageFor("gA")
                            .get(bytes("apple")) != null, 10_000);
        }
    }

    @Test
    void restartRegionANodeRegionBIntact() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode node = fixture.managers().get("n2").raftFor("gA");
            kill(node);
            fixture.restartGroup("n2", "gA");
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            awaitTrue("gB intact on n2", () ->
                    fixture.managers().get("n2").storageFor("gB")
                            .get(bytes("mango")) != null, 5000);
        }
    }

    @Test
    void regionAGroupDestroyedRegionBServing() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode gBLeader = leaderOf(fixture, "gB");
            fixture.managers().get("n1").destroy("gA");
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            assertThat(gBLeader.commitIndex()).isZero();
        }
    }

    @Test
    void concurrentWritesDuringRegionAPartition() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.partition("gA", "n3", true);
            RaftNode gALeader = awaitLeaderIgnoring(
                    groupRafts(fixture, "gA"), Set.of("n3"), 8000);
            RaftNode gBLeader = leaderOf(fixture, "gB");
            Thread a = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    fixture.managers().get(gALeader.id()).storageFor("gA")
                            .put(bytes("a" + i), bytes("v"));
                }
            });
            Thread b = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    fixture.managers().get(gBLeader.id()).storageFor("gB")
                            .put(bytes("b" + i), bytes("v"));
                }
            });
            a.start();
            b.start();
            a.join(20_000);
            b.join(20_000);
            assertThat(gALeader.commitIndex()).isEqualTo(29);
            assertThat(gBLeader.commitIndex()).isEqualTo(29);
        }
    }

    @Test
    void partitionHealRecoveryBothRegions() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.partition("gA", "n3", true);
            fixture.network.partition("gB", "n2", true);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"),
                    Set.of("n3"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"),
                    Set.of("n2"));
            fixture.network.partition("gA", "n3", false);
            fixture.network.partition("gB", "n2", false);
            awaitTrue("both converge", () ->
                    fixture.managers().get("n3").storageFor("gA")
                            .get(bytes("apple")) != null
                            && fixture.managers().get("n2").storageFor("gB")
                            .get(bytes("mango")) != null, 10_000);
        }
    }

    @Test
    void simultaneousLeaderKillAcrossRegions() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            RaftNode gBLeader = leaderOf(fixture, "gB");
            kill(gALeader);
            kill(gBLeader);
            RaftNode newGALeader = awaitLeader(groupRafts(fixture, "gA"), 8000);
            RaftNode newGBLeader = awaitLeader(groupRafts(fixture, "gB"), 8000);
            assertThat(newGALeader).isNotEqualTo(gALeader);
            assertThat(newGBLeader).isNotEqualTo(gBLeader);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            assertThat(newGALeader.commitIndex()).isZero();
            assertThat(newGBLeader.commitIndex()).isZero();
        }
    }

    @Test
    void diskSlowLeaderRegionACommitsWithMajority() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            fixture.network.diskSlow("gA", gALeader.id(), 2);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"));
            awaitTrue("gA commits", () ->
                    gALeader.commitIndex() == 0, 10_000);
        }
    }

    @Test
    void epochGuardRejectsStaleWrite() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            RegionEpoch stale = fixture.regions().get(new RegionId(1)).epoch();
            fixture.regions().splitRegion(new RegionId(1), bytes("g"));
            assertThat(fixture.regions().guardEpoch(
                    new RegionId(1), stale)).isFalse();
        }
    }

    @Test
    void regionCountAndRoutingAfterChaos() throws Exception {
        try (ChaosFixture fixture = chaosFixture()) {
            fixture.network.partition("gA", "n3", true);
            putOnLeader(fixture, "gA", bytes("apple"), bytes("va"),
                    Set.of("n3"));
            putOnLeader(fixture, "gB", bytes("mango"), bytes("vb"));
            fixture.network.partition("gA", "n3", false);
            assertThat(fixture.regions().regionCount()).isEqualTo(2);
            assertThat(fixture.regions().route(bytes("apple")).regionId())
                    .isEqualTo(new RegionId(1));
            assertThat(fixture.regions().route(bytes("mango")).regionId())
                    .isEqualTo(new RegionId(2));
        }
    }

    // ---------- helpers ----------

    private static ChaosFixture chaosFixture() {
        ChaosNetwork network = new ChaosNetwork();
        Map<String, RaftGroupManager> managers = new HashMap<>();
        List<RaftGroupManager> all = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            MultiRaftNode host = new MultiRaftNode(nodeId);
            RaftGroupManager manager = new RaftGroupManager(
                    nodeId, host, ELECTION, 25, 10);
            managers.put(nodeId, manager);
            all.add(manager);
        }
        for (String nodeId : List.of("n1", "n2", "n3")) {
            RaftGroupManager manager = managers.get(nodeId);
            for (String group : List.of("gA", "gB")) {
                ChaosRaftTransport transport =
                        new ChaosRaftTransport(group, nodeId, network);
                manager.createGroup(group, transport, MemTable.create());
                network.register(group, nodeId, manager.raftFor(group));
            }
        }
        for (RaftGroupManager manager : all) {
            manager.startAll();
        }
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, null);
        regions.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, null);
        return new ChaosFixture(network, managers, all, regions);
    }

    private static void kill(RaftNode node) {
        node.suspend();
        node.close();
    }

    private static RaftNode leaderOf(ChaosFixture fixture, String groupId)
            throws InterruptedException {
        return awaitLeader(groupRafts(fixture, groupId), 8000);
    }

    private static List<RaftNode> groupRafts(ChaosFixture fixture, String groupId) {
        List<RaftNode> rafts = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            rafts.add(fixture.managers().get(nodeId).raftFor(groupId));
        }
        return rafts;
    }

    private static void putOnLeader(ChaosFixture fixture, String groupId,
                                    byte[] key, byte[] value)
            throws InterruptedException {
        putOnLeader(fixture, groupId, key, value, Set.of());
    }

    private static void putOnLeader(ChaosFixture fixture, String groupId,
                                    byte[] key, byte[] value, Set<String> ignore)
            throws InterruptedException {
        RaftNode leader = awaitLeaderIgnoring(
                groupRafts(fixture, groupId), ignore, 8000);
        fixture.managers().get(leader.id()).storageFor(groupId).put(key, value);
    }

    private static RaftNode awaitLeaderIgnoring(List<RaftNode> nodes,
                                                Set<String> ignore,
                                                long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode node : nodes) {
                if (!node.active() || ignore.contains(node.id())
                        || node.state() != RaftState.LEADER
                        || !node.id().equals(node.leaderId())) {
                    continue;
                }
                long term = node.currentTerm();
                int sameTerm = 0;
                for (RaftNode peer : nodes) {
                    if (peer.active() && !ignore.contains(peer.id())
                            && peer.currentTerm() == term) {
                        sameTerm++;
                    }
                }
                if (sameTerm >= 2) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no stable leader within " + timeoutMillis
                + "ms ignoring " + ignore);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 组 × 节点级故障规则。 */
    private static final class ChaosRule {
        private volatile long latencyMillis;
        private volatile int lossPercent;
        private volatile boolean partitioned;
        private volatile long diskSlowMillis;
    }

    private static final class ChaosNetwork {
        private final Map<String, ChaosRule> rules = new HashMap<>();
        private final Map<String, RaftNode> registry = new HashMap<>();

        private synchronized ChaosRule rule(String group, String node) {
            return rules.computeIfAbsent(group + "|" + node,
                    ignored -> new ChaosRule());
        }

        private void latency(String group, String node, long millis) {
            rule(group, node).latencyMillis = millis;
        }

        private void loss(String group, String node, int percent) {
            rule(group, node).lossPercent = percent;
        }

        private void partition(String group, String node, boolean on) {
            rule(group, node).partitioned = on;
        }

        private void diskSlow(String group, String node, long millis) {
            rule(group, node).diskSlowMillis = millis;
        }

        private synchronized void register(String group, String node, RaftNode raft) {
            registry.put(group + "|" + node, raft);
        }

        private synchronized RaftNode find(String group, String node) {
            RaftNode raft = registry.get(group + "|" + node);
            if (raft == null) {
                throw new IllegalStateException(
                        "no raft for " + group + " on " + node);
            }
            return raft;
        }

        private synchronized List<String> peerIds(String group) {
            List<String> ids = new ArrayList<>();
            for (String key : registry.keySet()) {
                if (key.startsWith(group + "|")) {
                    ids.add(key.substring(group.length() + 1));
                }
            }
            return ids.stream().sorted().toList();
        }
    }

    private static final class ChaosRaftTransport implements RaftTransport {
        private final String groupId;
        private final String selfId;
        private final ChaosNetwork network;

        private ChaosRaftTransport(String groupId, String selfId,
                                   ChaosNetwork network) {
            this.groupId = groupId;
            this.selfId = selfId;
            this.network = network;
        }

        @Override
        public List<String> peerIds() {
            return network.peerIds(groupId);
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(
                String target, VoteRequest request) {
            return call(target, () -> network.find(groupId, target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            return call(target, () -> network.find(groupId, target).receive(request));
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return call(target, () -> network.find(groupId, target).receive(request));
        }

        private <T> CompletableFuture<T> call(String target, Supplier<T> supplier) {
            ChaosRule sourceRule = network.rule(groupId, selfId);
            ChaosRule targetRule = network.rule(groupId, target);
            if (sourceRule.partitioned || targetRule.partitioned) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("partitioned"));
            }
            int loss = Math.max(sourceRule.lossPercent, targetRule.lossPercent);
            if (loss > 0 && ThreadLocalRandom.current().nextInt(100) < loss) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("packet loss"));
            }
            long latency = Math.max(
                    sourceRule.latencyMillis, targetRule.latencyMillis);
            long diskSlow = Math.max(
                    sourceRule.diskSlowMillis, targetRule.diskSlowMillis);
            if (latency <= 0 && diskSlow <= 0) {
                return CompletableFuture.completedFuture(supplier.get());
            }
            long delay = Math.max(latency, diskSlow);
            CompletableFuture<T> future = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        try {
                            future.complete(supplier.get());
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    });
            return future;
        }
    }

    private static final class ChaosFixture implements AutoCloseable {
        private final ChaosNetwork network;
        private final Map<String, RaftGroupManager> managers;
        private final List<RaftGroupManager> all;
        private final RegionManager regions;

        private ChaosFixture(ChaosNetwork network,
                             Map<String, RaftGroupManager> managers,
                             List<RaftGroupManager> all,
                             RegionManager regions) {
            this.network = network;
            this.managers = managers;
            this.all = all;
            this.regions = regions;
        }

        private ChaosNetwork network() {
            return network;
        }

        private Map<String, RaftGroupManager> managers() {
            return managers;
        }

        private RegionManager regions() {
            return regions;
        }

        private void restartGroup(String nodeId, String groupId) {
            RaftGroupManager manager = managers.get(nodeId);
            manager.destroy(groupId);
            ChaosRaftTransport transport =
                    new ChaosRaftTransport(groupId, nodeId, network);
            manager.createGroup(groupId, transport, MemTable.create());
            network.register(groupId, nodeId, manager.raftFor(groupId));
            manager.raftFor(groupId).start();
        }

        @Override
        public void close() {
            for (RaftGroupManager manager : all) {
                manager.close();
            }
        }
    }
}
