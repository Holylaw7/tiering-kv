package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 自适应 Flush（ADR-0049）：动态间隔 / 水位 / 平滑 / 限幅 / 自动巡检。 */
class AdaptiveFlushControllerTest {

    @TempDir
    Path dir;

    @Test
    void lowLoadUsesLongInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        assertThat(controller.flushIntervalMillis()).isEqualTo(
                AdaptiveFlushController.MAX_INTERVAL_MILLIS);
    }

    @Test
    void highWriteRateShortensInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(200_000);
        assertThat(controller.flushIntervalMillis()).isEqualTo(
                AdaptiveFlushController.MIN_INTERVAL_MILLIS);
    }

    @Test
    void highQueueDepthShortensInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.setFlushQueueDepth(8);
        assertThat(controller.flushIntervalMillis()).isLessThan(
                AdaptiveFlushController.MAX_INTERVAL_MILLIS);
    }

    @Test
    void flushLatencyLengthensInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(100_000);
        long withoutLatency = controller.flushIntervalMillis();
        controller.recordFlush(200);
        assertThat(controller.flushIntervalMillis()).isGreaterThan(withoutLatency);
    }

    @Test
    void sstableCountLengthensInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(100_000);
        long withoutSstables = controller.flushIntervalMillis();
        controller.setSstableCount(32);
        assertThat(controller.flushIntervalMillis()).isGreaterThan(withoutSstables);
    }

    @Test
    void intervalClampedToBounds() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(10_000_000);
        controller.setFlushQueueDepth(10_000);
        assertThat(controller.flushIntervalMillis())
                .isGreaterThanOrEqualTo(AdaptiveFlushController.MIN_INTERVAL_MILLIS);
        assertThat(controller.flushIntervalMillis())
                .isLessThanOrEqualTo(AdaptiveFlushController.MAX_INTERVAL_MILLIS);
    }

    @Test
    void highWatermarkLowLoadIsHigh() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        assertThat(controller.highWatermark(0.1))
                .isEqualTo(AdaptiveFlushController.MAX_WATERMARK);
    }

    @Test
    void highWatermarkHighLoadIsLow() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(200_000);
        assertThat(controller.highWatermark(0.1))
                .isEqualTo(AdaptiveFlushController.MIN_WATERMARK);
    }

    @Test
    void shouldAutoFlushBelowWatermarkFalse() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        assertThat(controller.shouldAutoFlush(0.1)).isFalse();
    }

    @Test
    void shouldAutoFlushAboveWatermarkTrue() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(200_000);
        assertThat(controller.shouldAutoFlush(0.6)).isTrue();
    }

    @Test
    void emaSmoothsWriteRate() {
        AdaptiveFlushController controller = new AdaptiveFlushController(100_000, 0.5);
        controller.recordWriteRate(0);
        controller.recordWriteRate(100_000);
        controller.recordWriteRate(0);
        assertThat(controller.writeRate()).isBetween(0d, 100_000d);
    }

    @Test
    void recordFlushUpdatesLatency() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordFlush(50);
        assertThat(controller.flushLatencyMs()).isEqualTo(50);
    }

    @Test
    void sstableCountEffectOnInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.recordWriteRate(100_000);
        long before = controller.flushIntervalMillis();
        controller.setSstableCount(64);
        assertThat(controller.flushIntervalMillis()).isGreaterThan(before);
    }

    @Test
    void queueDepthEffectOnInterval() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        controller.setFlushQueueDepth(1);
        long depth1 = controller.flushIntervalMillis();
        controller.setFlushQueueDepth(8);
        assertThat(controller.flushIntervalMillis()).isLessThan(depth1);
    }

    @Test
    void watermarkClamped() {
        AdaptiveFlushController controller = AdaptiveFlushController.defaults();
        assertThat(controller.highWatermark(-1))
                .isGreaterThanOrEqualTo(AdaptiveFlushController.MIN_WATERMARK);
        assertThat(controller.highWatermark(2))
                .isLessThanOrEqualTo(AdaptiveFlushController.MAX_WATERMARK);
    }

    @Test
    void autoFlushTriggersOnMemoryPressure() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1024 * 1024));
        StorageMetrics metrics = new StorageMetrics();
        AdaptiveFlushController controller = new AdaptiveFlushController(100_000, 0.5);
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1, "auto-flush")) {
            FlushScheduler scheduler = new FlushScheduler(
                    pool, memTable, wal, cold, metrics, controller);
            controller.recordWriteRate(100_000);
            scheduler.startAutoFlush();
            for (int i = 0; i < 50_000; i++) {
                memTable.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[16]);
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && metrics.snapshot().flushCount() == 0) {
                Thread.sleep(20);
            }
            assertThat(pool.awaitIdle(30_000)).isTrue();
            assertThat(metrics.snapshot().flushCount()).isGreaterThanOrEqualTo(1);
            scheduler.close();
        }
    }
}
