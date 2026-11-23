package io.tieringkv.datamesh;

import io.tieringkv.datamesh.AutoTierManager.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自动分层（ADR-0187）：热度矩阵 → 分层。 */
class AutoTierManagerTest {

    @Test
    void noAccessCold() {
        AutoTierManager manager = new AutoTierManager();
        assertThat(manager.decide("v1", 100, 10))
                .isEqualTo(Tier.COLD);
        assertThat(manager.tier("v1")).isEqualTo(Tier.COLD);
    }

    @Test
    void warmThreshold() {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < 20; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.decide("v1", 100, 10))
                .isEqualTo(Tier.WARM);
    }

    @Test
    void hotThreshold() {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < 150; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.decide("v1", 100, 10))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void thresholdsInclusive() {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < 10; i++) {
            manager.recordAccess("warm");
        }
        for (int i = 0; i < 100; i++) {
            manager.recordAccess("hot");
        }
        assertThat(manager.decide("warm", 100, 10))
                .isEqualTo(Tier.WARM);
        assertThat(manager.decide("hot", 100, 10))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void accessCountTracked() {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < 42; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.accessCount("v1")).isEqualTo(42);
    }

    @Test
    void unknownViewCountZero() {
        assertThat(new AutoTierManager().accessCount("missing"))
                .isZero();
    }

    @Test
    void tiersSnapshot() {
        AutoTierManager manager = new AutoTierManager();
        manager.recordAccess("a");
        manager.recordAccess("b");
        manager.recordAccess("b");
        manager.decide("a", 100, 10);
        manager.decide("b", 100, 10);
        assertThat(manager.tiers()).containsEntry("a", Tier.COLD)
                .containsEntry("b", Tier.COLD);
    }

    @Test
    void resetCounts() {
        AutoTierManager manager = new AutoTierManager();
        manager.recordAccess("v1");
        manager.resetCounts();
        assertThat(manager.accessCount("v1")).isZero();
        assertThat(manager.viewIds()).isEmpty();
    }

    @Test
    void invalidThresholdsRejected() {
        AutoTierManager manager = new AutoTierManager();
        assertThatThrownBy(() -> manager.decide("v1", 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.decide("v1", 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankViewIdRejected() {
        AutoTierManager manager = new AutoTierManager();
        assertThatThrownBy(() -> manager.recordAccess(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.decide("", 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "access {0}")
    @ValueSource(ints = {0, 5, 10, 50, 100})
    void parameterizedAccessLevels(int access) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < access; i++) {
            manager.recordAccess("v1");
        }
        Tier tier = manager.decide("v1", 100, 10);
        if (access >= 100) {
            assertThat(tier).isEqualTo(Tier.HOT);
        } else if (access >= 10) {
            assertThat(tier).isEqualTo(Tier.WARM);
        } else {
            assertThat(tier).isEqualTo(Tier.COLD);
        }
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedThresholds(int threshold) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < threshold; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.decide("v1", threshold, 1))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void concurrentAccessStable() throws Exception {
        AutoTierManager manager = new AutoTierManager();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    manager.recordAccess("v1");
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(manager.accessCount("v1")).isEqualTo(400);
    }
}
