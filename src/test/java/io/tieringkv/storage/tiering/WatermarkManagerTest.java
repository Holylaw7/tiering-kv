package io.tieringkv.storage.tiering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatermarkManagerTest {

    private final WatermarkManager manager = new WatermarkManager(WatermarkManager.Config.defaults());

    @Test
    void normalBelowHighWatermark() {
        assertThat(manager.evaluate(800_000, 1_000_000, 100, 0)).isEqualTo(TierState.NORMAL);
    }

    @Test
    void warningAtHighWatermark() {
        assertThat(manager.evaluate(850_000, 1_000_000, 100, 0)).isEqualTo(TierState.WARNING);
    }

    @Test
    void criticalAtCriticalWatermark() {
        assertThat(manager.evaluate(950_000, 1_000_000, 100, 0)).isEqualTo(TierState.CRITICAL);
    }

    @Test
    void entryCountTriggersWarning() {
        assertThat(manager.evaluate(100_000, 1_000_000, 1_000_000, 0))
                .isEqualTo(TierState.WARNING);
    }

    @Test
    void queueThresholdsTriggerWarningAndCritical() {
        assertThat(manager.evaluate(100_000, 1_000_000, 0, 5_000)).isEqualTo(TierState.WARNING);
        assertThat(manager.evaluate(100_000, 1_000_000, 0, 10_000)).isEqualTo(TierState.CRITICAL);
    }

    @Test
    void flushNeededAtHighWatermark() {
        assertThat(manager.isFlushNeeded(850_000, 1_000_000, 0)).isTrue();
        assertThat(manager.isFlushNeeded(800_000, 1_000_000, 0)).isFalse();
    }
}
