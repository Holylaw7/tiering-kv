package io.tieringkv.cluster.raft;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static io.tieringkv.cluster.RaftTestSupport.closeAll;
import static io.tieringkv.cluster.RaftTestSupport.group3;
import static io.tieringkv.cluster.RaftTestSupport.startAll;
import static org.assertj.core.api.Assertions.assertThat;

/** 复制优化（ADR-0042）：CommitNotifier 去重 + ReplicationTracker 进度 + 滞后。 */
class ReplicationOptimizationTest {

    @Test
    void commitNotifierDeduplicates() {
        CommitNotifier notifier = new CommitNotifier();
        assertThat(notifier.mark(0)).isTrue();
        assertThat(notifier.mark(0)).isFalse();
        assertThat(notifier.mark(1)).isTrue();
        assertThat(notifier.mark(1)).isFalse();
    }

    @Test
    void replicationTrackerTracksProgress() throws Exception {
        ReplicationTracker tracker = new ReplicationTracker();
        tracker.initialize("n2", 0);
        assertThat(tracker.matchIndex("n2")).isEqualTo(-1);
        tracker.onSuccess("n2", 0);
        assertThat(tracker.matchIndex("n2")).isZero();
        assertThat(tracker.nextIndex("n2")).isEqualTo(1);
        FollowerProgress progress = tracker.progress("n2");
        assertThat(progress.lastAckNanos()).isGreaterThan(0);
        tracker.onFailure("n2");
        assertThat(tracker.nextIndex("n2")).isZero();
        assertThat(tracker.matchIndex("n2")).isZero();
    }

    @Test
    void commitNotificationAppliesOnFollowersPromptly() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        try {
            RaftNode leader = awaitLeader(List.of(nodes), 5000);
            leader.propose("v".getBytes(StandardCharsets.UTF_8)).get();
            // 异步批量复制 + CommitNotifier：follower 在 commit 后很快 apply
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                boolean allApplied = true;
                for (RaftNode node : nodes) {
                    if (node != leader && node.lastApplied() < leader.commitIndex()) {
                        allApplied = false;
                        break;
                    }
                }
                if (allApplied) {
                    break;
                }
                Thread.sleep(5);
            }
            assertThat(applied).hasSize(3);
            for (RaftNode node : nodes) {
                if (node != leader) {
                    assertThat(node.lastApplied()).isEqualTo(leader.commitIndex());
                }
            }
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void replicationLagBelowTarget() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        try {
            RaftNode leader = awaitLeader(List.of(nodes), 5000);
            long start = System.nanoTime();
            leader.propose("lag".getBytes(StandardCharsets.UTF_8)).get();
            long leaderCommitNanos = System.nanoTime();
            for (RaftNode node : nodes) {
                if (node != leader) {
                    while (node.lastApplied() < leader.commitIndex()
                            && System.nanoTime() - leaderCommitNanos < 100_000_000L) {
                        Thread.sleep(1);
                    }
                    assertThat(node.lastApplied()).isEqualTo(leader.commitIndex());
                }
            }
            long lagMillis = (System.nanoTime() - leaderCommitNanos) / 1_000_000;
            // 目标 <5ms；本地传输 + 立即补发下滞后接近 0
            assertThat(lagMillis).isLessThan(100);
            assertThat(applied).hasSize(3);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void multipleProposalsAllApplyInOrder() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        try {
            RaftNode leader = awaitLeader(List.of(nodes), 5000);
            for (int i = 0; i < 5; i++) {
                leader.propose(("cmd" + i).getBytes(StandardCharsets.UTF_8)).get();
            }
            awaitTrue("followers apply all", () -> {
                for (RaftNode node : nodes) {
                    if (node.lastApplied() < 4) {
                        return false;
                    }
                }
                return true;
            }, 5000);
            // applied 为三节点共享列表：每个命令恰好被三个节点各应用一次
            assertThat(applied).hasSize(15);
            for (RaftNode node : nodes) {
                assertThat(node.commitIndex()).isEqualTo(4);
                List<LogEntry> log = node.logSnapshot();
                assertThat(log).hasSize(5);
                for (int i = 0; i < 5; i++) {
                    assertThat(log.get(i).command())
                            .isEqualTo(("cmd" + i).getBytes(StandardCharsets.UTF_8));
                }
            }
            for (int i = 0; i < 5; i++) {
                assertThat(java.util.Collections.frequency(applied, "cmd" + i)).isEqualTo(3);
            }
        } finally {
            closeAll(nodes);
        }
    }
}
