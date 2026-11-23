package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.PlacementScheduler.Node;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PD 等价调度（ADR-0205）：放置/均衡/配额。 */
class PdSchedulersTest {

    @Test
    void placementToAz() {
        PlacementScheduler scheduler = scheduler();
        String node = scheduler.place("r1", "az-1", 0);
        assertThat(node).isEqualTo("n1");
        assertThat(scheduler.placement("r1")).isEqualTo("n1");
    }

    @Test
    void placementEpochMismatchRejected() {
        PlacementScheduler scheduler = scheduler();
        scheduler.advanceEpoch();
        assertThatThrownBy(() -> scheduler.place("r1", "az-1", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noNodeInAzRejected() {
        PlacementScheduler scheduler = scheduler();
        assertThatThrownBy(() -> scheduler.place("r1", "az-9", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void canPlaceChecksAz() {
        PlacementScheduler scheduler = scheduler();
        assertThat(scheduler.canPlace("n1", "az-1")).isTrue();
        assertThat(scheduler.canPlace("n1", "az-2")).isFalse();
    }

    @Test
    void blankRegionRejected() {
        assertThatThrownBy(() -> scheduler().place("", "az-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebalancePlanMovesExcess() {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        List<Move> moves = scheduler.plan(Map.of(
                "n1", 150L, "n2", 50L), 100);
        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).from()).isEqualTo("n1");
        assertThat(moves.get(0).to()).isEqualTo("n2");
        assertThat(moves.get(0).amount()).isEqualTo(50);
    }

    @Test
    void rebalanceBalancedNoMoves() {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        assertThat(scheduler.plan(Map.of(
                "n1", 100L, "n2", 100L), 100)).isEmpty();
    }

    @Test
    void rebalanceEmptyLoadsRejected() {
        assertThatThrownBy(() -> new RebalanceScheduler()
                .plan(Map.of(), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotaAcquireAndRemaining() {
        QuotaScheduler scheduler = new QuotaScheduler(3);
        assertThat(scheduler.tryAcquire()).isTrue();
        assertThat(scheduler.tryAcquire()).isTrue();
        assertThat(scheduler.tryAcquire()).isTrue();
        assertThat(scheduler.tryAcquire()).isFalse();
        assertThat(scheduler.remaining()).isZero();
        assertThat(scheduler.used()).isEqualTo(3);
    }

    @Test
    void quotaReset() {
        QuotaScheduler scheduler = new QuotaScheduler(1);
        scheduler.tryAcquire();
        scheduler.reset();
        assertThat(scheduler.tryAcquire()).isTrue();
    }

    @Test
    void negativeQuotaRejected() {
        assertThatThrownBy(() -> new QuotaScheduler(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotaUpdateApplies() {
        QuotaScheduler scheduler = new QuotaScheduler(1);
        scheduler.tryAcquire();
        scheduler.setQuota(2);
        assertThat(scheduler.tryAcquire()).isTrue();
    }

    @ParameterizedTest(name = "az {0}")
    @ValueSource(strings = {"az-1", "az-2", "az-3"})
    void parameterizedAzPlacement(String az) {
        PlacementScheduler scheduler = scheduler();
        String node = scheduler.place("r" + az, az, 0);
        assertThat(node).isNotNull();
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {1, 10, 100})
    void parameterizedQuotas(long quota) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        for (int i = 0; i < quota; i++) {
            assertThat(scheduler.tryAcquire()).isTrue();
        }
        assertThat(scheduler.tryAcquire()).isFalse();
    }

    @ParameterizedTest(name = "load {0}")
    @ValueSource(longs = {50, 100, 150, 200})
    void parameterizedRebalanceLoads(long load) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        List<Move> moves = scheduler.plan(Map.of(
                "n1", load, "n2", 50L), 100);
        if (load > 100) {
            assertThat(moves).isNotEmpty();
        } else {
            assertThat(moves).isEmpty();
        }
    }

    @Test
    void concurrentQuotaExact() throws Exception {
        QuotaScheduler scheduler = new QuotaScheduler(1000);
        java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    if (scheduler.tryAcquire()) {
                        accepted.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(accepted.get()).isEqualTo(1000);
    }

    private static PlacementScheduler scheduler() {
        PlacementScheduler scheduler = new PlacementScheduler();
        scheduler.registerNode(new Node("n1", "az-1"));
        scheduler.registerNode(new Node("n2", "az-2"));
        scheduler.registerNode(new Node("n3", "az-3"));
        return scheduler;
    }
}
