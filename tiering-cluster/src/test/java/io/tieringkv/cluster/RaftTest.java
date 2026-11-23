package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.tieringkv.cluster.RaftTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaftTest {

    @Test
    void singleNodeBecomesLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("solo leader", () -> node.state() == RaftState.LEADER, 3000);
        node.close();
    }

    @Test
    void threeNodesElectSingleLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        int leaders = 0;
        for (RaftNode node : nodes) {
            if (node.state() == RaftState.LEADER) {
                leaders++;
            }
        }
        assertThat(leaders).isEqualTo(1);
        assertThat(leader.leaderId()).isEqualTo(leader.id());
        closeAll(nodes);
    }

    @Test
    void termsIncreaseAfterElection() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        awaitLeader(List.of(nodes), 5000);
        long term = nodes[0].currentTerm();
        assertThat(term).isGreaterThanOrEqualTo(1);
        closeAll(nodes);
    }

    @Test
    void voteGrantedOncePerTerm() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode follower = null;
        for (RaftNode node : nodes) {
            if (node.state() == RaftState.FOLLOWER) {
                follower = node;
                break;
            }
        }
        assertThat(follower).isNotNull();
        long term = follower.currentTerm() + 5;
        VoteRequest first = new VoteRequest(term, "c1", 0, 0);
        VoteRequest second = new VoteRequest(term, "c2", 0, 0);
        assertThat(follower.receive(first).granted()).isTrue();
        assertThat(follower.receive(second).granted()).isFalse(); // 每任期一票
        closeAll(nodes);
    }

    @Test
    void staleTermVoteRejected() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode node = nodes[0];
        long futureTerm = node.currentTerm() + 10;
        node.receive(new VoteRequest(futureTerm, "x", 0, 0));
        assertThat(node.receive(new VoteRequest(futureTerm - 1, "y", 0, 0)).granted())
                .isFalse();
        closeAll(nodes);
    }

    @Test
    void heartbeatKeepsFollowerStable() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        Thread.sleep(500);
        assertThat(leader.state()).isEqualTo(RaftState.LEADER);
        long leaders = List.of(nodes).stream().filter(n -> n.state() == RaftState.LEADER).count();
        assertThat(leaders).isEqualTo(1);
        closeAll(nodes);
    }

    @Test
    void proposeOnLeaderAppliesCommand() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("A".getBytes(StandardCharsets.UTF_8)).get();
        awaitTrue("applied", () -> applied.contains("A"), 2000);
        node.close();
    }

    @Test
    void proposeOnFollowerFails() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        awaitLeader(List.of(nodes), 5000);
        awaitTrue("follower exists", () ->
                java.util.Arrays.stream(nodes).anyMatch(n -> n.state() == RaftState.FOLLOWER), 3000);
        RaftNode follower = java.util.Arrays.stream(nodes)
                .filter(n -> n.state() == RaftState.FOLLOWER).findFirst().orElseThrow();
        assertThatThrownBy(() -> follower.propose("X".getBytes()).get())
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        closeAll(nodes);
    }

    @Test
    void logReplicatesToAllFollowers() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        leader.propose("hello".getBytes(StandardCharsets.UTF_8)).get();
        for (RaftNode node : nodes) {
            awaitTrue(node.id() + " log", () -> node.logSize() == 1, 3000);
            awaitTrue(node.id() + " applied", () -> applied.size() >= 1, 3000);
        }
        closeAll(nodes);
    }

    @Test
    void commitIndexAdvancesOnLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("A".getBytes()).get();
        assertThat(node.commitIndex()).isEqualTo(0);
        node.close();
    }

    @Test
    void followerAppliesCommittedEntry() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        leader.propose("v".getBytes(StandardCharsets.UTF_8)).get();
        awaitTrue("applied on followers", () -> applied.size() >= 2, 3000);
        closeAll(nodes);
    }

    @Test
    void prevLogMismatchRejected() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        // follower 日志与 leader 期望的 prevLog 不一致 → 拒绝
        var response = node.receive(new AppendEntriesRequest(
                1, "leader", 5, 1, List.of(), 0));
        assertThat(response.success()).isFalse();
        node.close();
    }

    @Test
    void conflictingSuffixIsTruncatedOnAppend() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        // 先构造 follower 日志 [A(term1), X(term2)]
        node.receive(new AppendEntriesRequest(
                2, "l1", -1, 0,
                List.of(new LogEntry(1, 0, "A".getBytes()),
                        new LogEntry(2, 1, "X".getBytes())), 0));
        // leader(term3) 从 prevLog(0, term1) 覆盖：冲突后缀应被截断
        var response = node.receive(new AppendEntriesRequest(
                3, "l2", 0, 1,
                List.of(new LogEntry(3, 1, "B".getBytes())), 0));
        assertThat(response.success()).isTrue();
        assertThat(node.logSnapshot()).hasSize(2);
        assertThat(node.logSnapshot().get(1).command())
                .isEqualTo("B".getBytes(StandardCharsets.UTF_8));
        node.close();
    }

    @Test
    void truncatedPendingProposalFailsInsteadOfCompleting() throws Exception {
        List<String> applied = new ArrayList<>();
        StubTransport transport = new StubTransport();
        RaftNode node = new RaftNode("n1", transport,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                ELECTION, 25, 10, new MemoryRaftLog(), null, null);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        CompletableFuture<Long> pending = node.propose("X".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(100);
        assertThat(pending.isDone()).isFalse(); // 无多数派 ack，提案保持悬挂
        long term = node.currentTerm() + 5;
        AppendEntriesResponse response = node.receive(new AppendEntriesRequest(
                term, "new-leader", -1, 0,
                List.of(new LogEntry(term, 0, "Y".getBytes(StandardCharsets.UTF_8))), 0));
        assertThat(response.success()).isTrue();
        // 冲突截断后旧提案必须显式失败，禁止被新条目虚假完成（Phase 15 混沌发现）
        assertThatThrownBy(pending::join)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("superseded");
        node.close();
    }

    @Test
    void emptyHeartbeatMustNotCommitConflictingEntry() throws Exception {
        List<String> applied = new ArrayList<>();
        StubTransport transport = new StubTransport();
        RaftNode node = new RaftNode("n1", transport,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                ELECTION, 25, 10, new MemoryRaftLog(), null, null);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        CompletableFuture<Long> pending = node.propose("X".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(100);
        assertThat(pending.isDone()).isFalse(); // 无多数派 ack，提案保持悬挂
        long term = node.currentTerm() + 5;
        // 新 leader 日志在 idx0 是另一条命令；空心跳只确认 prevLog(-1)，
        // leaderCommit=0 不得把 follower 的冲突条目（term1, X）提交
        AppendEntriesResponse response = node.receive(new AppendEntriesRequest(
                term, "new-leader", -1, 0, List.of(), 0));
        assertThat(response.success()).isTrue();
        assertThat(node.commitIndex()).isEqualTo(-1);
        assertThat(node.lastApplied()).isEqualTo(-1);
        assertThat(applied).isEmpty();
        assertThat(pending.isDone()).isFalse();
        node.close();
    }

    @Test
    void higherTermHeartbeatStepsDownLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        leader.receive(new AppendEntriesRequest(
                leader.currentTerm() + 5, "new-leader", -1, 0, List.of(), 0));
        awaitTrue("stepped down", () -> leader.state() == RaftState.FOLLOWER, 2000);
        closeAll(nodes);
    }

    @Test
    void logAppendPreservesOrder() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("a".getBytes()).get();
        node.propose("b".getBytes()).get();
        assertThat(applied).containsExactly("a", "b");
        node.close();
    }

    @Test
    void logEntriesCarryLeaderTerm() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("a".getBytes()).get();
        assertThat(node.logSnapshot().get(0).term()).isEqualTo(node.currentTerm());
        node.close();
    }

    @Test
    void emptyLogFollowerCatchesUp() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        RaftNode lagging = List.of(nodes).stream()
                .filter(n -> n != leader).findFirst().orElseThrow();
        lagging.close(); // 停止其调度器（receive 仍可用，日志为空）
        leader.propose("sync".getBytes(StandardCharsets.UTF_8)).get();
        awaitTrue("lagging catches up", () -> lagging.logSize() == 1, 5000);
        closeAll(nodes);
    }

    @Test
    void majorityRequiredForCommit() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (RaftNode node : nodes) {
            if (node != leader) {
                node.suspend();
            }
        }
        leader.propose("lonely".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);
        assertThat(leader.commitIndex()).isEqualTo(-1); // 单节点不构成多数派
        closeAll(nodes);
    }

    @Test
    void leaderContinuesAfterReplicaCrash() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (RaftNode node : nodes) {
            if (node != leader) {
                node.suspend();
                break; // 仅模拟单个 follower 故障
            }
        }
        leader.propose("ok".getBytes(StandardCharsets.UTF_8)).get();
        assertThat(leader.commitIndex()).isEqualTo(0);
        closeAll(nodes);
    }

    @Test
    void stateMachineApplyOrderIsFifo() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("1".getBytes()).get();
        node.propose("2".getBytes()).get();
        node.propose("3".getBytes()).get();
        assertThat(applied).containsExactly("1", "2", "3");
        node.close();
    }

    @Test
    void logSnapshotIsCopy() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode node = node("solo", List.of(), applied);
        node.start();
        awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
        node.propose("a".getBytes()).get();
        List<LogEntry> snapshot = node.logSnapshot();
        node.propose("b".getBytes()).get();
        assertThat(snapshot).hasSize(1);
        node.close();
    }

    @Test
    void newLeaderBackfillsLaggingFollowerWithoutNewWrites() throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        List<RaftNode> nodes = new ArrayList<>();
        List<BackfillTransport> transports = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            BackfillTransport transport = new BackfillTransport(peers, id);
            // n1 快速选举确保为 leader，避免 n3（目标滞后副本）当选
            io.tieringkv.cluster.raft.LeaderElection election =
                    id.equals("n1")
                            ? new io.tieringkv.cluster.raft.LeaderElection(12, 4)
                            : ELECTION;
            RaftNode node = new RaftNode(id, transport,
                    (index, command) -> applied.add(
                            new String(command, StandardCharsets.UTF_8)),
                    election, 25, 10, new MemoryRaftLog(), null, null);
            nodes.add(node);
            transports.add(transport);
        }
        peers.addAll(nodes);
        startAll(nodes.toArray(new RaftNode[0]));
        RaftNode leader = awaitLeader(nodes, 5000);
        leader.propose(bytes("A")).get();
        awaitTrue("all have A", () ->
                nodes.stream().allMatch(n -> n.logSize() == 1), 3000);
        // 断开 leader → n3 的复制（n3 滞后）
        // 全部传输丢弃到 n3：避免选举切换后新 leader 绕开丢弃
        for (BackfillTransport transport : transports) {
            transport.dropTarget = "n3";
        }
        leader.propose(bytes("B")).get();
        assertThat(nodes.stream().filter(n -> n.id().equals("n3"))
                .findFirst().orElseThrow().logSize()).isEqualTo(1);
        for (BackfillTransport transport : transports) {
            transport.dropTarget = null;
        }
        // 击杀 leader，n2 以非空日志当选
        leader.suspend();
        leader.close();
        RaftNode newLeader = awaitLeader(nodes, 5000);
        assertThat(newLeader.id()).isNotEqualTo(leader.id());
        // 关键回归：无新写入，滞后副本必须被回填
        awaitTrue("lagging follower backfilled", () ->
                nodes.stream().filter(n -> n.id().equals("n3"))
                        .findFirst().orElseThrow().logSize() == 2, 5000);
        closeAll(nodes.toArray(new RaftNode[0]));
    }

    /** 授予选票但从不响应日志复制的传输：用于构造悬挂提案。 */
    private static final class StubTransport implements RaftTransport {

        @Override
        public List<String> peerIds() {
            return List.of("n1", "p1", "p2");
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            return CompletableFuture.completedFuture(new VoteResponse(request.term(), true));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return new CompletableFuture<>();
        }
    }

    /** 可定向丢弃 AppendEntries 的传输（滞后副本回填回归）。 */
    private static final class BackfillTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final String selfId;
        private volatile String dropTarget;

        private BackfillTransport(List<RaftNode> peers, String selfId) {
            this.peers = peers;
            this.selfId = selfId;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(
                String target, VoteRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            if (target.equals(dropTarget)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("dropped"));
            }
            return CompletableFuture.completedFuture(find(target).receive(request));
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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
