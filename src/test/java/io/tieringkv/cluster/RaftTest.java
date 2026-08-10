package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.VoteRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

}
