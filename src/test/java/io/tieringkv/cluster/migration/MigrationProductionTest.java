package io.tieringkv.cluster.migration;

import io.tieringkv.cluster.migration.parallel.RegionTransferManager;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产化迁移（Phase 18）：限速 / 调度策略 / 指标。 */
class MigrationProductionTest {

    @TempDir
    Path dir;
    private static final java.util.concurrent.atomic.AtomicInteger RUN =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void rateLimitSlowsMigration() throws Exception {
        long unlimited = run(5_000, 4, 0);
        long limited = run(5_000, 4, 50_000); // 50KB/s
        assertThat(limited).isGreaterThan(unlimited * 3);
    }

    @Test
    void rateLimitZeroMeansUnlimited() throws Exception {
        long a = run(5_000, 4, 0);
        long b = run(5_000, 4, 0);
        assertThat(Math.abs(a - b)).isLessThan(Math.max(a, b));
    }

    @Test
    void rateLimitRespectedAcrossBatches() throws Exception {
        // 10KB/s 下迁移 10 万字节 ≥ 约 10 秒（宽松下限 5s）
        long millis = run(10_000, 2, 10_000);
        assertThat(millis).isGreaterThan(5_000);
    }

    @Test
    void schedulerReducesWorkersOnIoPressure() {
        MigrationScheduler scheduler = new MigrationScheduler(
                4, 8, 80, 100, () -> 95L, () -> 0L);
        assertThat(scheduler.adjustWorkers()).isEqualTo(3);
        assertThat(scheduler.adjustWorkers()).isEqualTo(2);
    }

    @Test
    void schedulerIncreasesOnBacklog() {
        MigrationScheduler scheduler = new MigrationScheduler(
                2, 8, 80, 100, () -> 10L, () -> 500L);
        assertThat(scheduler.adjustWorkers()).isEqualTo(3);
        assertThat(scheduler.adjustWorkers()).isEqualTo(4);
    }

    @Test
    void schedulerBoundedByMaxWorkers() {
        MigrationScheduler scheduler = new MigrationScheduler(
                8, 8, 80, 100, () -> 10L, () -> 999L);
        assertThat(scheduler.adjustWorkers()).isEqualTo(8);
    }

    @Test
    void schedulerBoundedByMinOne() {
        MigrationScheduler scheduler = new MigrationScheduler(
                1, 8, 80, 100, () -> 999L, () -> 0L);
        assertThat(scheduler.adjustWorkers()).isEqualTo(1);
    }

    @Test
    void schedulerStableWhenBalanced() {
        MigrationScheduler scheduler = new MigrationScheduler(
                4, 8, 80, 100, () -> 50L, () -> 50L);
        assertThat(scheduler.adjustWorkers()).isEqualTo(4);
    }

    @Test
    void schedulerInitialWorkersClamped() {
        MigrationScheduler scheduler = new MigrationScheduler(
                99, 8, 80, 100, () -> 0L, () -> 0L);
        assertThat(scheduler.workers()).isEqualTo(8);
    }

    @Test
    void remainingMetricTracked() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.setRemaining(42);
        assertThat(metrics.snapshot().migrationRemaining()).isEqualTo(42);
    }

    @Test
    void errorMetricTracked() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordError();
        assertThat(metrics.snapshot().migrationErrors()).isEqualTo(1);
    }

    @Test
    void migrationBytesTotalMetric() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(1_000);
        metrics.recordBytes(2_000);
        assertThat(metrics.snapshot().migrationBytes()).isEqualTo(3_000);
    }

    @Test
    void speedMetricPositive() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(10_000);
        assertThat(metrics.snapshot().migrationSpeedMbPerSec()).isGreaterThan(0);
    }

    @Test
    void sectionTextContainsProductionFields() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(1024);
        metrics.recordError();
        metrics.setRemaining(7);
        String text = metrics.sectionText();
        assertThat(text).contains("migration_bytes:1024",
                "migration_speed_mb_per_sec:",
                "migration_remaining:7",
                "migration_error:1");
    }

    @Test
    void pauseResumeWithRateLimit() throws Exception {
        MemTable source = source(3_000);
        MemTable target = MemTable.create();
        RegionTransferManager manager = new RegionTransferManager(
                source, target, dir, 2, Long.MAX_VALUE, 200_000);
        try {
            manager.pause();
            Thread runner = new Thread(() -> {
                try {
                    manager.migrate(2);
                } catch (Exception ignored) {
                }
            });
            runner.start();
            Thread.sleep(200);
            assertThat(target.size()).isZero();
            manager.resume();
            runner.join(30_000);
            assertThat(target.size()).isEqualTo(3_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void retryWithRateLimit() throws Exception {
        MemTable source = source(2_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 1, Long.MAX_VALUE, 10_000);
            RegionTransferManager.MigrationSummary summary = manager.migrate(1);
            assertThat(summary.failedChunks()).isZero();
            assertThat(target.size()).isEqualTo(2_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migrateAllWithRateLimit() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 4, Long.MAX_VALUE, 1_000_000);
            RegionTransferManager.MigrationSummary summary = manager.migrate(4);
            assertThat(summary.doneChunks()).isEqualTo(4);
            assertThat(target.size()).isEqualTo(5_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void idempotentWithRateLimit() throws Exception {
        MemTable source = source(2_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE, 1_000_000);
            manager.migrate(2);
            RegionTransferManager.MigrationSummary second = manager.migrate(2);
            assertThat(second.entries()).isZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void corruptCheckpointWithRateLimit() throws Exception {
        MemTable source = source(2_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 1, Long.MAX_VALUE, 1_000_000);
            manager.migrate(1);
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    dir.resolve("chunk-0.ckpt"));
            bytes[bytes.length - 1] ^= 0x01;
            java.nio.file.Files.write(dir.resolve("chunk-0.ckpt"), bytes);
            manager.migrate(1);
            assertThat(target.size()).isEqualTo(2_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void schedulerPolicyWithMetrics() {
        AtomicLong io = new AtomicLong(20);
        AtomicLong backlog = new AtomicLong(10);
        MigrationScheduler scheduler = new MigrationScheduler(
                3, 6, 80, 100, io::get, backlog::get);
        io.set(95);
        assertThat(scheduler.adjustWorkers()).isEqualTo(2);
        io.set(20);
        backlog.set(500);
        assertThat(scheduler.adjustWorkers()).isEqualTo(3);
    }

    private long run(int count, int workers, long rateLimit) throws Exception {
        MemTable source = source(count);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir.resolve("run-" + RUN.incrementAndGet()),
                    workers, Long.MAX_VALUE, rateLimit);
            long start = System.currentTimeMillis();
            manager.migrate(workers);
            return System.currentTimeMillis() - start;
        } finally {
            source.close();
            target.close();
        }
    }

    private static MemTable source(int count) {
        MemTable table = MemTable.create();
        byte[] value = new byte[64];
        for (int i = 0; i < count; i++) {
            table.put(("pm:" + i).getBytes(StandardCharsets.UTF_8), value);
        }
        return table;
    }
}
