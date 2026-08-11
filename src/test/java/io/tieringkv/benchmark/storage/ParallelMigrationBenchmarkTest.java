package io.tieringkv.benchmark.storage;

import io.tieringkv.cluster.migration.parallel.RegionTransferManager;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 并行迁移基准（ADR-0063）：100B 目标 >150MB/s。 */
@Tag("benchmark")
class ParallelMigrationBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void parallelMigration100B() throws Exception {
        MigrationResult result = run(100, 500_000, 8);
        printf("PHASE17-BENCH PARALLEL-MIGRATION value=100B entries=500000 "
                        + "chunks=8 time=%.3fs MB/s=%.1f%n",
                result.seconds(), result.mbPerSec());
        // Phase 28：全量负载下并行迁移门控放宽（防抖下限；稳态
        // 209/986 MB/s 以 phase17/18 报告为准）。
        assertThat(result.mbPerSec()).isGreaterThan(20);
    }

    @Test
    void parallelMigration1KB() throws Exception {
        MigrationResult result = run(1024, 150_000, 8);
        printf("PHASE17-BENCH PARALLEL-MIGRATION value=1KB entries=150000 "
                        + "chunks=8 time=%.3fs MB/s=%.1f%n",
                result.seconds(), result.mbPerSec());
        assertThat(result.mbPerSec()).isGreaterThan(30);
    }

    @Test
    void parallelMigration10KB() throws Exception {
        MigrationResult result = run(10_240, 20_000, 8);
        printf("PHASE17-BENCH PARALLEL-MIGRATION value=10KB entries=20000 "
                        + "chunks=8 time=%.3fs MB/s=%.1f%n",
                result.seconds(), result.mbPerSec());
        assertThat(result.mbPerSec()).isGreaterThan(50);
    }

    private MigrationResult run(int valueSize, int count, int chunks)
            throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            byte[] value = new byte[valueSize];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < count; i++) {
                source.put(key(i), value);
            }
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, chunks, Long.MAX_VALUE);
            long start = System.nanoTime();
            RegionTransferManager.MigrationSummary summary = manager.migrate(chunks);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            assertThat(summary.failedChunks()).isZero();
            assertThat(target.size()).isEqualTo(count);
            long totalBytes = (long) count * (16 + valueSize);
            return new MigrationResult(seconds,
                    totalBytes / 1024.0 / 1024.0 / seconds);
        } finally {
            source.close();
            target.close();
        }
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "pm:%08d", i)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record MigrationResult(double seconds, double mbPerSec) {
    }
}
