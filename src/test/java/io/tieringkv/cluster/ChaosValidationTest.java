package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 混沌验证（Phase 15）：网络延迟/丢包/分区/磁盘慢/leader 击杀，
 * 验证：无数据丢失、选举恢复、replica catch-up、已提交写入不丢失。
 * 故障注入在 RaftTransport 层模拟 tc netem 语义（Windows 环境无 netem）。
 */
class ChaosValidationTest {

    @TempDir
    Path dir;

    @Test
    void networkLatency100msStillCommits() throws Exception {
        // 单向延迟 100ms：选举超时需大于首条心跳到达时间，避免反复竞选
        try (ChaosFixture fixture = start(false, null, Map.of(),
                new LeaderElection(250, 200))) {
            fixture.network.latency("n1", 100);
            fixture.network.latency("n2", 100);
            fixture.network.latency("n3", 100);
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            putThroughLeader(fixture, key(0), value(0));
            putThroughLeader(fixture, key(1), value(1));
            awaitAllSee(fixture.nodes, key(1), 8000);
        }
    }

    @Test
    void packetLoss5PercentNoDataLoss() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            fixture.network.loss("n1", 5);
            fixture.network.loss("n2", 5);
            fixture.network.loss("n3", 5);
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            for (int i = 0; i < 30; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(29), 8000);
            assertAllActiveSee(fixture.nodes, 30);
        }
    }

    @Test
    void packetLoss10PercentRecovery() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            fixture.network.loss("n1", 10);
            fixture.network.loss("n2", 10);
            fixture.network.loss("n3", 10);
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            for (int i = 0; i < 20; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(19), 10_000);
            assertAllActiveSee(fixture.nodes, 20);
        }
    }

    @Test
    void partitionFollowerNoDataLossAndHeal() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            fixture.network.partition(follower.id(), true);
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            assertThat(follower.get(key(0))).isNull(); // 分区期间不接收
            awaitSee(fixture.nodes, activeIds(fixture.nodes, follower.id()), key(9), 8000);
            fixture.network.partition(follower.id(), false);
            awaitAllSee(fixture.nodes, key(9), 10_000);
            assertAllActiveSee(fixture.nodes, 10);
        }
    }

    @Test
    void partitionLeaderTriggersFailover() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode oldLeader = awaitLeader(fixture.nodes, null, 8000);
            fixture.network.partition(oldLeader.id(), true);
            ClusterNode newLeader = awaitLeader(fixture.nodes, oldLeader.id(), 8000);
            assertThat(newLeader.id()).isNotEqualTo(oldLeader.id());
            newLeader.put(key(0), value(0));
            awaitSee(fixture.nodes, activeIds(fixture.nodes, oldLeader.id()), key(0), 8000);
            fixture.network.partition(oldLeader.id(), false);
            awaitTrue("old leader steps down after heal",
                    () -> oldLeader.raft().state() == RaftState.FOLLOWER, 8000);
            awaitAllSee(fixture.nodes, key(0), 10_000);
        }
    }

    @Test
    void diskSlowFollowerStillCommits() throws Exception {
        try (ChaosFixture fixture = start(false, "n1", Map.of("n2", 5L))) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            assertThat(leader.id()).isEqualTo("n1");
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(9), 10_000);
        }
    }

    @Test
    void diskSlowLeaderCommitsWithMajority() throws Exception {
        try (ChaosFixture fixture = start(false, "n1", Map.of("n1", 2L))) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            assertThat(leader.id()).isEqualTo("n1");
            for (int i = 0; i < 5; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(4), 10_000);
        }
    }

    @Test
    void leaderKillNoDataLoss() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(9), 8000);
            kill(leader);
            ClusterNode newLeader = awaitLeader(fixture.nodes, leader.id(), 8000);
            // Raft：新 leader 必须先提交自己 term 的条目，才会 apply 旧 term 已提交条目
            newLeader.put(bytes("probe"), value(99));
            awaitAllSee(fixture.nodes, key(9), 10_000);
            assertAllActiveSee(fixture.nodes, 10);
        }
    }

    @Test
    void replicaRestartCatchesUp() throws Exception {
        try (ChaosFixture fixture = start(true, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            awaitAllSee(fixture.nodes, key(9), 8000);
            kill(follower);
            for (int i = 10; i < 20; i++) {
                leader.put(key(i), value(i));
            }
            awaitSee(fixture.nodes, activeIds(fixture.nodes, follower.id()), key(19), 8000);
            restart(fixture, follower.id());
            awaitAllSee(fixture.nodes, key(19), 10_000);
            assertAllActiveSee(fixture.nodes, 20);
        }
    }

    @Test
    void killOneFollowerMajorityKeepsCommitting() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            kill(follower);
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            awaitSee(fixture.nodes, activeIds(fixture.nodes, follower.id()), key(9), 8000);
        }
    }

    @Test
    void minorityRestartRecoversAllData() throws Exception {
        try (ChaosFixture fixture = start(true, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            kill(follower);
            for (int i = 0; i < 10; i++) {
                leader.put(key(i), value(i));
            }
            awaitSee(fixture.nodes, activeIds(fixture.nodes, follower.id()), key(9), 8000);
            restart(fixture, follower.id());
            awaitAllSee(fixture.nodes, key(9), 10_000);
            assertAllActiveSee(fixture.nodes, 10);
        }
    }

    @Test
    void mixedChaosSequenceNoDataLoss() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            // 阶段1：100ms 全链路延迟
            fixture.network.latency("n1", 100);
            fixture.network.latency("n2", 100);
            fixture.network.latency("n3", 100);
            for (int i = 0; i < 5; i++) {
                putThroughLeader(fixture, key(i), value(i));
            }
            fixture.network.latency("n1", 0);
            fixture.network.latency("n2", 0);
            fixture.network.latency("n3", 0);
            // 阶段2：10% 丢包
            fixture.network.loss("n1", 10);
            fixture.network.loss("n2", 10);
            fixture.network.loss("n3", 10);
            for (int i = 5; i < 10; i++) {
                putThroughLeader(fixture, key(i), value(i));
            }
            fixture.network.loss("n1", 0);
            fixture.network.loss("n2", 0);
            fixture.network.loss("n3", 0);
            // 阶段3：分区一个 follower 后恢复
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            fixture.network.partition(follower.id(), true);
            for (int i = 10; i < 15; i++) {
                putThroughLeader(fixture, key(i), value(i));
            }
            fixture.network.partition(follower.id(), false);
            awaitAllSee(fixture.nodes, key(14), 10_000);
            // 阶段4：击杀 leader，新 leader 继续写
            kill(leader);
            ClusterNode newLeader = awaitLeader(fixture.nodes, leader.id(), 8000);
            for (int i = 15; i < 20; i++) {
                newLeader.put(key(i), value(i));
            }
            awaitSee(fixture.nodes, activeIds(fixture.nodes, leader.id()), key(19), 8000);
            assertAllActiveSee(fixture.nodes, 20);
        }
    }

    @Test
    void laggingReplicaCatchesUpAfterHeal() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ClusterNode follower = firstFollower(fixture.nodes, leader.id());
            fixture.network.partition(follower.id(), true);
            for (int i = 0; i < 20; i++) {
                leader.put(key(i), value(i));
            }
            fixture.network.partition(follower.id(), false);
            awaitAllSee(fixture.nodes, key(19), 10_000);
            assertAllActiveSee(fixture.nodes, 20);
        }
    }

    @Test
    void concurrentWritesLeaderKillNoLostCommitted() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < 4; t++) {
                    final int writer = t;
                    for (int j = 0; j < 25; j++) {
                        int index = writer * 25 + j;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            try {
                                leader.putAsync(key(index), value(index))
                                        .get(5, TimeUnit.SECONDS);
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        }, pool));
                    }
                }
                Thread.sleep(150);
                kill(leader);
                int committed = 0;
                for (CompletableFuture<Boolean> future : futures) {
                    if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
                        committed++;
                    }
                }
                assertThat(committed).isGreaterThan(0);
                ClusterNode newLeader = awaitLeader(fixture.nodes, leader.id(), 8000);
                // 新 leader 提交探针条目后，旧 term 已提交条目才会 apply
                newLeader.put(bytes("probe"), value(99));
                for (int i = 0; i < 100; i++) {
                    if (futures.get(i).join()) {
                        assertThat(newLeader.get(key(i))).isEqualTo(value(i));
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void diskSlowThenLeaderKillRecovery() throws Exception {
        try (ChaosFixture fixture = start(true, "n1", Map.of("n1", 2L))) {
            ClusterNode oldLeader = awaitLeader(fixture.nodes, null, 8000);
            assertThat(oldLeader.id()).isEqualTo("n1");
            for (int i = 0; i < 5; i++) {
                oldLeader.put(key(i), value(i));
            }
            // kill 前必须确认副本收敛，避免日志长度不一致导致选举竞争
            awaitAllSee(fixture.nodes, key(4), 10_000);
            kill(oldLeader);
            ClusterNode newLeader = awaitLeader(fixture.nodes, oldLeader.id(), 8000);
            newLeader.put(bytes("probe"), value(99)); // 触发旧 term 已提交条目 apply
            awaitAllSee(fixture.nodes, key(4), 10_000);
            restart(fixture, oldLeader.id());
            awaitAllSee(fixture.nodes, key(4), 10_000);
        }
    }

    @Test
    void quorumLossBlocksCommitUntilFailover() throws Exception {
        try (ChaosFixture fixture = start(false, null, Map.of())) {
            ClusterNode oldLeader = awaitLeader(fixture.nodes, null, 8000);
            Set<String> followers = new LinkedHashSet<>(fixture.nodes.keySet());
            followers.remove(oldLeader.id());
            for (String follower : followers) {
                fixture.network.partitionBetween(oldLeader.id(), follower, true);
            }
            CompletableFuture<Void> pending = oldLeader.putAsync(key(0), value(0));
            assertThatThrownBy(() -> pending.get(800, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            // 分区期间必须选出新 leader（旧提案仍悬挂，绝不能提交）
            ClusterNode newLeader = awaitLeader(fixture.nodes, oldLeader.id(), 8000);
            assertThat(newLeader.id()).isNotEqualTo(oldLeader.id());
            for (String follower : followers) {
                fixture.network.partitionBetween(oldLeader.id(), follower, false);
            }
            newLeader.put(key(1), value(1));
            awaitSee(fixture.nodes, activeIds(fixture.nodes, oldLeader.id()), key(1), 8000);
            // 无法定数的旧提案绝不能虚假成功：必须被显式失败（冲突截断）
            awaitTrue("old proposal settles as failed", pending::isDone, 15_000);
            assertThat(pending.isCompletedExceptionally()).isTrue();
            assertThatThrownBy(pending::join)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    // ---------- helpers ----------

    private ChaosFixture start(boolean persistent, String fastLeaderId,
                               Map<String, Long> appendDelays) throws Exception {
        return start(persistent, fastLeaderId, appendDelays, null);
    }

    private ChaosFixture start(boolean persistent, String fastLeaderId,
                               Map<String, Long> appendDelays,
                               LeaderElection electionOverride) throws Exception {
        ChaosNetwork network = new ChaosNetwork();
        Map<String, ClusterNode> nodes = new HashMap<>();
        for (String id : List.of("n1", "n2", "n3")) {
            MemTable local = MemTable.create();
            RaftLog log;
            RaftPersistentState state = null;
            if (persistent) {
                log = FileRaftLog.open(dir.resolve(id).resolve("raftlog"), Durability.SYNC);
                state = RaftPersistentState.open(dir.resolve(id));
            } else {
                log = new MemoryRaftLog();
            }
            if (appendDelays.containsKey(id)) {
                log = new SlowRaftLog(log, appendDelays.get(id));
            }
            LeaderElection election = electionOverride != null ? electionOverride
                    : id.equals(fastLeaderId)
                    ? new LeaderElection(12, 4)
                    : new LeaderElection(100, 80);
            ChaosTransport transport = new ChaosTransport(id, network);
            ClusterNode node = ClusterNode.createPersistent(
                    id, transport, local, election, 25, 10, log, state, null);
            network.register(id, node.raft());
            nodes.put(id, node);
        }
        for (ClusterNode node : nodes.values()) {
            node.start();
        }
        return new ChaosFixture(network, nodes);
    }

    private void restart(ChaosFixture fixture, String id) throws Exception {
        ClusterNode old = fixture.nodes.remove(id);
        if (old != null) {
            old.close();
        }
        MemTable local = MemTable.create();
        RaftLog log = FileRaftLog.open(dir.resolve(id).resolve("raftlog"), Durability.SYNC);
        RaftPersistentState state = RaftPersistentState.open(dir.resolve(id));
        ChaosTransport transport = new ChaosTransport(id, fixture.network);
        ClusterNode node = ClusterNode.createPersistent(
                id, transport, local, new LeaderElection(100, 80), 25, 10, log, state, null);
        fixture.network.register(id, node.raft());
        fixture.nodes.put(id, node);
        node.start();
    }

    private static void kill(ClusterNode node) {
        node.raft().suspend();
        node.raft().close();
    }

    private static ClusterNode awaitLeader(Map<String, ClusterNode> nodes, String excludeId,
                                           long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : nodes.values()) {
                RaftNode raft = node.raft();
                if (!raft.active() || node.id().equals(excludeId)
                        || raft.state() != RaftState.LEADER
                        || !node.id().equals(raft.leaderId())) {
                    continue;
                }
                long term = raft.currentTerm();
                int sameTerm = 0;
                for (ClusterNode peer : nodes.values()) {
                    if (peer.raft().active() && peer.raft().currentTerm() == term) {
                        sameTerm++;
                    }
                }
                if (sameTerm >= 2) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        List<String> states = new ArrayList<>();
        for (ClusterNode node : nodes.values()) {
            states.add(node.id() + "=" + node.raft().state()
                    + " term=" + node.raft().currentTerm()
                    + (node.raft().active() ? "" : " inactive"));
        }
        throw new AssertionError("no stable leader within " + timeoutMillis + "ms: " + states);
    }

    /** 通过当前 leader 写入；leader 迁移时自动重新解析（最多 10s）。 */
    private static void putThroughLeader(ChaosFixture fixture, byte[] key, byte[] value)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode leader = awaitLeader(fixture.nodes, null, 8000);
            try {
                leader.put(key, value);
                return;
            } catch (RuntimeException e) {
                Thread.sleep(20); // 领导权可能已迁移，重试
            }
        }
        throw new AssertionError("put failed across leader changes");
    }

    private static ClusterNode firstFollower(Map<String, ClusterNode> nodes, String leaderId) {
        for (ClusterNode node : nodes.values()) {
            if (!node.id().equals(leaderId) && node.raft().active()) {
                return node;
            }
        }
        throw new AssertionError("no active follower");
    }

    private static Set<String> activeIds(Map<String, ClusterNode> nodes, String... exclude) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> excluded = Set.of(exclude);
        for (ClusterNode node : nodes.values()) {
            if (node.raft().active() && !excluded.contains(node.id())) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    private static void awaitSee(Map<String, ClusterNode> nodes, Set<String> ids,
                                 byte[] key, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (String id : ids) {
                ClusterNode node = nodes.get(id);
                if (node == null || !node.raft().active() || node.get(key) == null) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("nodes " + ids + " did not converge within "
                + timeoutMillis + "ms");
    }

    private static void awaitAllSee(Map<String, ClusterNode> nodes, byte[] key,
                                    long timeoutMillis) throws InterruptedException {
        awaitSee(nodes, activeIds(nodes), key, timeoutMillis);
    }

    private static void awaitTrue(String message, java.util.function.BooleanSupplier condition,
                                  long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    private static void assertAllActiveSee(Map<String, ClusterNode> nodes, int count) {
        for (ClusterNode node : nodes.values()) {
            if (!node.raft().active()) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                assertThat(node.get(key(i))).as(node.id() + " key " + i)
                        .isEqualTo(value(i));
            }
        }
    }

    private static byte[] key(int i) {
        return ("chaos:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("v" + i).getBytes(StandardCharsets.UTF_8);
    }

    /** 节点故障规则：延迟/丢包/分区（transport 层模拟 tc netem）。 */
    private static final class ChaosRule {
        private volatile long latencyMillis;
        private volatile int lossPercent;
        private volatile boolean partitioned;
    }

    private static final class ChaosNetwork {
        private final Map<String, ChaosRule> rules = new HashMap<>();
        private final Map<String, RaftNode> registry = new HashMap<>();
        private final Set<String> partitionedPairs = new HashSet<>();

        private synchronized ChaosRule rule(String id) {
            return rules.computeIfAbsent(id, ignored -> new ChaosRule());
        }

        private void latency(String id, long millis) {
            rule(id).latencyMillis = millis;
        }

        private void loss(String id, int percent) {
            rule(id).lossPercent = percent;
        }

        private void partition(String id, boolean partitioned) {
            rule(id).partitioned = partitioned;
        }

        /** 仅断开两节点之间的链路（follower 间仍可通信）。 */
        private void partitionBetween(String a, String b, boolean partitioned) {
            String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            if (partitioned) {
                partitionedPairs.add(pair);
            } else {
                partitionedPairs.remove(pair);
            }
        }

        private boolean partitionedBetween(String a, String b) {
            String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            return partitionedPairs.contains(pair);
        }

        private synchronized void register(String id, RaftNode node) {
            registry.put(id, node);
        }

        private synchronized RaftNode find(String id) {
            return registry.get(id);
        }

        private synchronized List<String> peerIds() {
            return registry.keySet().stream().sorted().toList();
        }
    }

    private static final class ChaosTransport implements RaftTransport {
        private final String sourceId;
        private final ChaosNetwork network;

        private ChaosTransport(String sourceId, ChaosNetwork network) {
            this.sourceId = sourceId;
            this.network = network;
        }

        @Override
        public List<String> peerIds() {
            return network.peerIds();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            return call(target, () -> network.find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            return call(target, () -> network.find(target).receive(request));
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return call(target, () -> network.find(target).receive(request));
        }

        private <T> CompletableFuture<T> call(String target, Supplier<T> supplier) {
            ChaosRule sourceRule = network.rule(sourceId);
            ChaosRule targetRule = network.rule(target);
            if (sourceRule.partitioned || targetRule.partitioned
                    || network.partitionedBetween(sourceId, target)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("partitioned"));
            }
            int loss = Math.max(sourceRule.lossPercent, targetRule.lossPercent);
            if (loss > 0 && ThreadLocalRandom.current().nextInt(100) < loss) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("packet loss"));
            }
            long latency = Math.max(sourceRule.latencyMillis, targetRule.latencyMillis);
            if (latency <= 0) {
                return CompletableFuture.completedFuture(supplier.get());
            }
            // 异步延迟完成：避免阻塞 Raft 调度线程，破坏心跳节奏
            CompletableFuture<T> future = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(latency, TimeUnit.MILLISECONDS)
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

    /** 慢磁盘日志：append 时模拟磁盘延迟。 */
    private static final class SlowRaftLog implements RaftLog {
        private final RaftLog delegate;
        private final long appendDelayMillis;

        private SlowRaftLog(RaftLog delegate, long appendDelayMillis) {
            this.delegate = delegate;
            this.appendDelayMillis = appendDelayMillis;
        }

        @Override
        public void append(LogEntry entry) {
            try {
                Thread.sleep(appendDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delegate.append(entry);
        }

        @Override
        public LogEntry entryAt(long index) {
            return delegate.entryAt(index);
        }

        @Override
        public List<LogEntry> entriesFrom(long from) {
            return delegate.entriesFrom(from);
        }

        @Override
        public long firstIndex() {
            return delegate.firstIndex();
        }

        @Override
        public long lastIndex() {
            return delegate.lastIndex();
        }

        @Override
        public long lastTerm() {
            return delegate.lastTerm();
        }

        @Override
        public long termAt(long index) {
            return delegate.termAt(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void truncateFrom(long index) {
            delegate.truncateFrom(index);
        }

        @Override
        public void installSnapshot(long lastIncludedIndex) {
            delegate.installSnapshot(lastIncludedIndex);
        }

        @Override
        public void sync() {
            delegate.sync();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record ChaosFixture(ChaosNetwork network, Map<String, ClusterNode> nodes)
            implements AutoCloseable {

        @Override
        public void close() {
            for (ClusterNode node : nodes.values()) {
                node.close();
            }
        }
    }
}
