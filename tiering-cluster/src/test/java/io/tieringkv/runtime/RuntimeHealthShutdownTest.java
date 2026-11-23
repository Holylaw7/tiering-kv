package io.tieringkv.runtime;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 健康检查与优雅停机（ADR-0096）。 */
class RuntimeHealthShutdownTest {

    @Test
    void healthJsonContainsFields() {
        RuntimeHealth health = new RuntimeHealth(List.of(),
                () -> 2, () -> 3);
        String json = health.json();
        assertThat(json).contains("health");
        assertThat(json).contains("pending_txn");
        assertThat(json).contains("lock_count");
    }

    @Test
    void readinessFalseWithoutLeader() {
        RuntimeHealth health = new RuntimeHealth(List.of(),
                () -> 0, () -> 0);
        assertThat(health.readiness()).isFalse();
        assertThat(health.liveness()).isTrue();
    }

    @Test
    void readinessTrueWithLeader() throws Exception {
        List<RaftNode> peers = new java.util.ArrayList<>();
        RaftNode node = new RaftNode("n1", peers,
                (i, c) -> {
                }, new LeaderElection(50, 30), 25, 10);
        peers.add(node);
        node.start();
        Thread.sleep(300);
        RuntimeHealth health = new RuntimeHealth(List.of(node),
                () -> 0, () -> 0);
        assertThat(health.readiness()).isTrue();
        assertThat(health.json()).contains("leader");
        node.close();
    }

    @Test
    void shutdownDrainsInflight() {
        AtomicBoolean inflight = new AtomicBoolean(true);
        AtomicInteger stopped = new AtomicInteger();
        AtomicBoolean flushed = new AtomicBoolean();
        Runnable hook = GracefulShutdown.signalHook(stopped::incrementAndGet,
                () -> !inflight.get(), () -> 2_000L, () -> flushed.set(true),
                List.of());
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(50);
                inflight.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        closer.start();
        hook.run();
        assertThat(stopped.get()).isEqualTo(1);
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void shutdownTimeoutThenFlush() {
        AtomicBoolean flushed = new AtomicBoolean();
        boolean drained = GracefulShutdown.shutdown(() -> {
        }, () -> false, () -> 50L, () -> flushed.set(true), List.of());
        assertThat(drained).isFalse();
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void shutdownClosesResources() {
        AtomicBoolean closed = new AtomicBoolean();
        GracefulShutdown.shutdown(() -> {
        }, () -> true, () -> 100L, () -> {
        }, List.<AutoCloseable>of(() -> closed.set(true)));
        assertThat(closed.get()).isTrue();
    }

    @ParameterizedTest(name = "drain {0}")
    @ValueSource(ints = {10, 50, 100, 200})
    void parameterizedDrain(int delayMillis) {
        AtomicBoolean inflight = new AtomicBoolean(true);
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                inflight.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        closer.start();
        boolean drained = GracefulShutdown.shutdown(() -> {
        }, () -> !inflight.get(), () -> 1_000L, () -> {
        }, List.of());
        assertThat(drained).isTrue();
    }
}
