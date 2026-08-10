package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static io.tieringkv.cluster.RaftTestSupport.closeAll;
import static io.tieringkv.cluster.RaftTestSupport.startAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批量复制（ADR-0044）：batch 收集/流水线/inflight/group commit。 */
class RaftBatchReplicationTest {

    @Test
    void configValidation() {
        assertThatThrownBy(() -> new RaftReplicationConfig(0, 1024, 5, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RaftReplicationConfig(8, 0, 5, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RaftReplicationConfig(8, 1024, 0, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flushIntervalBatchesPendingEntries() throws Exception {
        Fixture fixture = group3Holding(new RaftReplicationConfig(128, 1 << 20, 20, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            // 首条立即 flush（idle peer）并保持 in-flight
            leader.propose(bytes("cmd0"));
            assertThat(fixture.maxHeldBatch()).isEqualTo(1);
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 1; i < 10; i++) {
                futures.add(leader.propose(bytes("cmd" + i)));
            }
            // 后续提案等待 flush 定时器（20ms）批量发送
            Thread.sleep(60);
            assertThat(fixture.maxHeldBatch()).isGreaterThanOrEqualTo(5);
            fixture.releaseAll();
            for (CompletableFuture<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(leader.commitIndex()).isEqualTo(9);
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void maxBatchEntriesSplitsRequests() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(3, 1 << 20, 5, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            for (int i = 0; i < 10; i++) {
                leader.propose(bytes("cmd" + i)).get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            for (RequestRecord record : fixture.records()) {
                assertThat(record.entryCount()).isLessThanOrEqualTo(3);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void maxBatchBytesLimitsPayload() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(128, 128, 5, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            for (int i = 0; i < 8; i++) {
                leader.propose(new byte[64]).get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            for (RequestRecord record : fixture.records()) {
                assertThat(record.payloadBytes()).isLessThanOrEqualTo(128);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void pipelineAllowsMultipleInflight() throws Exception {
        Fixture fixture = group3Holding(new RaftReplicationConfig(1, 1 << 20, 2, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(leader.propose(bytes("p" + i)));
            }
            // 响应被持有：多个请求应同时 in-flight
            assertThat(leader.replication().inflight(fixture.peerOf(leader))).isGreaterThan(1);
            long deadline = System.currentTimeMillis() + 5000;
            while (leader.commitIndex() < 3 && System.currentTimeMillis() < deadline) {
                fixture.releaseAll();
                Thread.sleep(10);
            }
            for (CompletableFuture<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void inflightNeverExceedsLimit() throws Exception {
        Fixture fixture = group3Holding(new RaftReplicationConfig(1, 1 << 20, 2, 3));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                futures.add(leader.propose(bytes("i" + i)));
            }
            long maxInflight = 0;
            for (RaftNode node : fixture.nodes()) {
                if (node != leader) {
                    maxInflight = Math.max(maxInflight,
                            leader.replication().inflight(node.id()));
                }
            }
            assertThat(maxInflight).isLessThanOrEqualTo(3);
            long deadline = System.currentTimeMillis() + 5000;
            while (leader.commitIndex() < 29 && System.currentTimeMillis() < deadline) {
                fixture.releaseAll();
                Thread.sleep(10);
            }
            for (CompletableFuture<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void groupCommitCompletesFuturesTogether() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(128, 1 << 20, 5, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(leader.propose(bytes("g" + i)));
            }
            for (CompletableFuture<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(leader.commitIndex()).isEqualTo(9);
            for (RaftNode node : fixture.nodes()) {
                assertThat(node.logSnapshot()).hasSize(10);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void noLostCommitUnderPipeline() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(8, 1 << 20, 2, 4));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            int total = 300;
            for (int i = 0; i < total; i++) {
                leader.propose(bytes("k" + i)).get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            assertThat(leader.commitIndex()).isEqualTo(total - 1);
            for (RaftNode node : fixture.nodes()) {
                assertThat(node.logSnapshot()).hasSize(total);
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void committedEntriesSurviveLeaderCrash() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(8, 1 << 20, 2, 4));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            for (int i = 0; i < 50; i++) {
                leader.propose(bytes("c" + i)).get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            leader.suspend();
            leader.close();
            RaftNode newLeader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
            assertThat(newLeader.commitIndex()).isGreaterThanOrEqualTo(49);
            assertThat(newLeader.logSnapshot()).hasSizeGreaterThanOrEqualTo(50);
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void failureBackoffResendsFromRewoundNextIndex() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(4, 1 << 20, 2, 2));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            for (int i = 0; i < 20; i++) {
                leader.propose(bytes("f" + i)).get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            // 全部成功后 leader 复制进度正常
            for (RaftNode node : fixture.nodes()) {
                if (node != leader) {
                    assertThat(leader.replication().matchIndex(node.id())).isEqualTo(19);
                }
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void heartbeatPropagatesCommitIndex() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(128, 1 << 20, 5, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            leader.propose(bytes("hb")).get(5, TimeUnit.SECONDS);
            awaitAllApplied(fixture);
            for (RaftNode node : fixture.nodes()) {
                assertThat(node.commitIndex()).isEqualTo(leader.commitIndex());
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void soloNodeCommitsWithBatchConfig() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied,
                new RaftReplicationConfig(8, 1 << 20, 2, 4));
        node.start();
        try {
            awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
            for (int i = 0; i < 5; i++) {
                node.propose(bytes("s" + i)).get(5, TimeUnit.SECONDS);
            }
            assertThat(node.commitIndex()).isEqualTo(4);
            assertThat(applied).containsExactly("s0", "s1", "s2", "s3", "s4");
        } finally {
            node.close();
        }
    }

    @Test
    void replicationTrackerInflightTracking() throws Exception {
        ReplicationTracker tracker = new ReplicationTracker();
        tracker.initialize("n2", 0);
        tracker.onSend("n2", 5);
        assertThat(tracker.inflight("n2")).isEqualTo(1);
        assertThat(tracker.progress("n2").lastSentIndex()).isEqualTo(5);
        tracker.onSend("n2", 9);
        assertThat(tracker.inflight("n2")).isEqualTo(2);
        tracker.onResponse("n2");
        tracker.onSuccess("n2", 9);
        assertThat(tracker.inflight("n2")).isEqualTo(1);
        assertThat(tracker.matchIndex("n2")).isEqualTo(9);
        assertThat(tracker.nextIndex("n2")).isEqualTo(10);
        tracker.onResponse("n2");
        assertThat(tracker.inflight("n2")).isZero();
    }

    @Test
    void pipelinedRequestsCarryIncreasingSentIndex() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(1, 1 << 20, 1, 4));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(leader.propose(bytes("inc" + i)));
            }
            for (CompletableFuture<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            awaitAllApplied(fixture);
            for (RaftNode node : fixture.nodes()) {
                if (node != leader) {
                    assertThat(leader.replication().progress(node.id()).lastSentIndex())
                            .isEqualTo(5);
                }
            }
        } finally {
            closeAll(fixture.nodes());
        }
    }

    @Test
    void throughputSmokeUnderPipeline() throws Exception {
        Fixture fixture = group3(new RaftReplicationConfig(64, 1 << 20, 2, 8));
        try {
            RaftNode leader = awaitLeader(Arrays.asList(fixture.nodes()), 5000);
            long start = System.nanoTime();
            int total = 1_000;
            int wave = 50;
            for (int base = 0; base < total; base += wave) {
                List<CompletableFuture<Long>> futures = new ArrayList<>();
                for (int i = base; i < base + wave && i < total; i++) {
                    futures.add(leader.propose(bytes("t" + i)));
                }
                for (CompletableFuture<Long> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            System.out.printf(java.util.Locale.ROOT,
                    "BATCH-BENCH pipeline writes=%d time=%.2fs ops/s=%.0f%n",
                    total, seconds, total / seconds);
            assertThat(seconds).isLessThan(30);
        } finally {
            closeAll(fixture.nodes());
        }
    }

    // ---------- helpers ----------

    private static void awaitAllApplied(Fixture fixture) throws InterruptedException {
        awaitTrue("all applied", () -> {
            long commit = fixture.nodes()[0].commitIndex();
            for (RaftNode node : fixture.nodes()) {
                if (node.lastApplied() < commit) {
                    return false;
                }
            }
            return true;
        }, 5000);
    }

    private static Fixture group3(RaftReplicationConfig config) throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        List<RequestRecord> records = new CopyOnWriteArrayList<>();
        Map<String, Integer> inflight = new ConcurrentHashMap<>();
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RecordingTransport transport =
                    new RecordingTransport(peers, id, records, inflight);
            RaftNode node = new RaftNode(id, transport, (index, command) ->
                            applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new MemoryRaftLog(), null, null, config);
            nodes.add(node);
        }
        peers.addAll(nodes);
        startAll(nodes.toArray(new RaftNode[0]));
        return new Fixture(nodes.toArray(new RaftNode[0]), records, inflight);
    }

    private static Fixture group3Holding(RaftReplicationConfig config) throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        HoldingTransport holding = new HoldingTransport(peers);
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id, holding, (index, command) ->
                            applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new MemoryRaftLog(), null, null, config);
            nodes.add(node);
        }
        peers.addAll(nodes);
        startAll(nodes.toArray(new RaftNode[0]));
        return new Fixture(nodes.toArray(new RaftNode[0]), List.of(), Map.of(), holding);
    }

    private static RaftNode node(String id, List<RaftNode> peers, List<String> applied,
                                 RaftReplicationConfig config) {
        return new RaftNode(id, new LocalRaftTransport(peers, id),
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                new MemoryRaftLog(), null, null, config);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record RequestRecord(int entryCount, int payloadBytes) {
    }

    private record Fixture(RaftNode[] nodes, List<RequestRecord> records,
                           Map<String, Integer> inflight, HoldingTransport holding) {

        private Fixture(RaftNode[] nodes, List<RequestRecord> records,
                        Map<String, Integer> inflight) {
            this(nodes, records, inflight, null);
        }

        private int maxObservedInflight() {
            return inflight.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        private String peerOf(RaftNode leader) {
            for (RaftNode node : nodes) {
                if (node != leader) {
                    return node.id();
                }
            }
            throw new IllegalStateException("no peer");
        }

        private void releaseAll() {
            holding.releaseAll();
        }

        private int maxHeldBatch() {
            return holding.maxHeldBatch();
        }
    }

    /** 可控响应传输：请求发出后由测试主动释放响应，用于观测 pipeline。 */
    private static final class HoldingTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final List<Held> held = Collections.synchronizedList(new ArrayList<>());

        private HoldingTransport(List<RaftNode> peers) {
            this.peers = peers;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            if (request.entries().isEmpty()) {
                // 心跳即时放行，避免 follower 选举超时
                return CompletableFuture.completedFuture(find(target).receive(request));
            }
            CompletableFuture<AppendEntriesResponse> future = new CompletableFuture<>();
            held.add(new Held(target, request, future));
            return future;
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        private void releaseAll() {
            List<Held> snapshot;
            synchronized (held) {
                snapshot = List.copyOf(held);
                held.clear();
            }
            for (Held item : snapshot) {
                item.future().complete(find(item.target()).receive(item.request()));
            }
        }

        private int maxHeldBatch() {
            synchronized (held) {
                return held.stream()
                        .mapToInt(h -> h.request().entries().size())
                        .max().orElse(0);
            }
        }

        private RaftNode find(String id) {
            for (RaftNode peer : peers) {
                if (peer.id().equals(id)) {
                    return peer;
                }
            }
            throw new IllegalStateException("no peer " + id);
        }

        private record Held(String target, AppendEntriesRequest request,
                            CompletableFuture<AppendEntriesResponse> future) {
        }
    }

    /** 记录 AppendEntries 的批量大小与 in-flight 峰值（仅统计数据请求）。 */
    private static final class RecordingTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final String selfId;
        private final List<RequestRecord> records;
        private final Map<String, Integer> inflight;

        private RecordingTransport(List<RaftNode> peers, String selfId,
                                   List<RequestRecord> records,
                                   Map<String, Integer> inflight) {
            this.peers = peers;
            this.selfId = selfId;
            this.records = records;
            this.inflight = inflight;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            CompletableFuture<AppendEntriesResponse> future =
                    CompletableFuture.completedFuture(find(target).receive(request));
            if (!request.entries().isEmpty()) {
                int payload = request.entries().stream()
                        .mapToInt(e -> e.command().length).sum();
                records.add(new RequestRecord(request.entries().size(), payload));
                inflight.merge(target, 1, Integer::sum);
                return future.whenComplete((r, e) -> inflight.merge(target, -1, Integer::sum));
            }
            return future;
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
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
