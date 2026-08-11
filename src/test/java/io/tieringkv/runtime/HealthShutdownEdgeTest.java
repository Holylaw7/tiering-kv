package io.tieringkv.runtime;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 健康探针与优雅停机边界（ADR-0096）。 */
class HealthShutdownEdgeTest {

    private final List<RaftNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (RaftNode node : nodes) {
            node.close();
        }
    }

    @Test
    void readinessFalseBeforeStart() {
        RaftNode node = raftNode();
        assertThat(node.state()).isEqualTo(RaftState.FOLLOWER);
        assertThat(new RuntimeHealth(List.of(node), () -> 0, () -> 0)
                .readiness()).isFalse();
    }

    @Test
    void readinessTrueAfterElection() throws Exception {
        RaftNode node = raftNode();
        node.start();
        awaitLeader(node);
        assertThat(new RuntimeHealth(List.of(node), () -> 0, () -> 0)
                .readiness()).isTrue();
    }

    @Test
    void readinessFalseAfterSuspend() throws Exception {
        RaftNode node = raftNode();
        node.start();
        awaitLeader(node);
        node.suspend();
        assertThat(new RuntimeHealth(List.of(node), () -> 0, () -> 0)
                .readiness()).isFalse();
    }

    @Test
    void livenessAlwaysTrueBeforeStart() {
        assertThat(new RuntimeHealth(List.of(raftNode()), () -> 9, () -> 9)
                .liveness()).isTrue();
    }

    @Test
    void jsonReportsNoLeader() {
        String json = new RuntimeHealth(List.of(raftNode()), () -> 2, () -> 3)
                .json();
        assertThat(json).contains("\"leader\":\"none\"");
        assertThat(json).contains("\"term\":0");
    }

    @Test
    void jsonReportsLeaderIdAndTerm() throws Exception {
        RaftNode node = raftNode();
        node.start();
        awaitLeader(node);
        String json = new RuntimeHealth(List.of(node), () -> 0, () -> 0)
                .json();
        assertThat(json).contains("\"leader\":\"n1\"");
        assertThat(json).contains("\"readiness\":true");
    }

    @Test
    void jsonReportsPendingAndLockCounts() {
        String json = new RuntimeHealth(List.of(), () -> 5, () -> 9).json();
        assertThat(json).contains("\"pending_txn\":5");
        assertThat(json).contains("\"lock_count\":9");
    }

    @Test
    void closerExceptionIsolated() {
        AtomicInteger closed = new AtomicInteger();
        List<AutoCloseable> closers = List.of(
                (AutoCloseable) () -> {
                    throw new IllegalStateException("boom");
                },
                (AutoCloseable) () -> closed.incrementAndGet());
        boolean drained = GracefulShutdown.shutdown(() -> {
        }, () -> true, () -> 100L, () -> {
        }, closers);
        assertThat(drained).isTrue();
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void zeroTimeoutStillFlushes() {
        AtomicBoolean flushed = new AtomicBoolean();
        boolean drained = GracefulShutdown.shutdown(() -> {
        }, () -> true, () -> 0L, () -> flushed.set(true), List.of());
        assertThat(drained).isTrue();
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void zeroTimeoutWithInflightAbortsDrainButFlushes() {
        AtomicBoolean flushed = new AtomicBoolean();
        boolean drained = GracefulShutdown.shutdown(() -> {
        }, () -> false, () -> 0L, () -> flushed.set(true), List.of());
        assertThat(drained).isFalse();
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void signalHookRunsShutdown() {
        AtomicInteger stopped = new AtomicInteger();
        AtomicBoolean flushed = new AtomicBoolean();
        Runnable hook = GracefulShutdown.signalHook(stopped::incrementAndGet,
                () -> true, () -> 100L, () -> flushed.set(true), List.of());
        hook.run();
        assertThat(stopped.get()).isEqualTo(1);
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void interruptDuringDrainAborts() throws Exception {
        AtomicBoolean drained = new AtomicBoolean(true);
        AtomicBoolean flushed = new AtomicBoolean();
        Thread shutdown = new Thread(() -> {
            boolean result = GracefulShutdown.shutdown(() -> {
            }, () -> false, () -> 10_000L, () -> flushed.set(true),
                    List.of());
            drained.set(result);
        });
        shutdown.start();
        Thread.sleep(100);
        shutdown.interrupt();
        shutdown.join(2_000);
        assertThat(drained.get()).isFalse();
        assertThat(flushed.get()).isTrue();
    }

    @ParameterizedTest(name = "closers {0}")
    @ValueSource(ints = {0, 1, 3, 5, 8})
    void parameterizedCloserCount(int count) {
        AtomicInteger closed = new AtomicInteger();
        List<AutoCloseable> closers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            closers.add(() -> closed.incrementAndGet());
        }
        GracefulShutdown.shutdown(() -> {
        }, () -> true, () -> 100L, () -> {
        }, closers);
        assertThat(closed.get()).isEqualTo(count);
    }

    private RaftNode raftNode() {
        RaftNode node = new RaftNode("n1", new ArrayList<>(),
                (index, command) -> {
                }, new LeaderElection(50, 30), 25, 10);
        nodes.add(node);
        return node;
    }

    private static void awaitLeader(RaftNode node) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (node.state() == RaftState.LEADER
                    && node.id().equals(node.leaderId())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("single node did not become leader");
    }
}
