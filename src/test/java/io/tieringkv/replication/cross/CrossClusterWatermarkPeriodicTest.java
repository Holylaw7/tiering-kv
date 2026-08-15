package io.tieringkv.replication.cross;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 水位周期刷盘（ADR-0333）：定时 checkpoint + close 兜底。 */
class CrossClusterWatermarkPeriodicTest {

    @TempDir
    Path dir;

    @Test
    void periodicCheckpointFlushesRecords() throws Exception {
        Path file = dir.resolve("wm-periodic.bin");
        try (CrossClusterWatermark watermark =
                     new CrossClusterWatermark(file)) {
            watermark.startPeriodicCheckpoint(50);
            assertThat(watermark.periodicCheckpointRunning()).isTrue();
            watermark.record("r1", 5);

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(3);
            long restored = -1;
            while (System.nanoTime() < deadline) {
                if (java.nio.file.Files.exists(file)) {
                    restored = CrossClusterWatermark.load(file)
                            .watermark("r1");
                    if (restored == 5) {
                        break;
                    }
                }
                Thread.sleep(25);
            }
            assertThat(restored).isEqualTo(5);
        }
    }

    @Test
    void closeStopsPeriodicSchedulerAndFlushes() throws Exception {
        Path file = dir.resolve("wm-close.bin");
        CrossClusterWatermark watermark =
                new CrossClusterWatermark(file);
        watermark.startPeriodicCheckpoint(10_000);
        watermark.record("r9", 9);
        watermark.close();
        assertThat(watermark.periodicCheckpointRunning()).isFalse();
        assertThat(CrossClusterWatermark.load(file)
                .watermark("r9")).isEqualTo(9);
    }

    @Test
    void invalidIntervalRejected() {
        CrossClusterWatermark watermark =
                new CrossClusterWatermark(dir.resolve("wm.bin"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> watermark.startPeriodicCheckpoint(0));
    }
}
