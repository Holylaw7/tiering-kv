package io.tieringkv.benchmark.storage;

import io.tieringkv.cluster.migration.streaming.BatchEncoder;
import io.tieringkv.cluster.migration.streaming.StreamingMigrator;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零拷贝迁移基准（ADR-0059 / Phase 16）：与 Phase 15 拷贝路径对比。
 * 目标：100B >100MB/s、1KB >300MB/s。
 */
@Tag("benchmark")
class ZeroCopyMigrationBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void zeroCopyMigration100B() throws Exception {
        MigrationResult result = run(100, 500_000);
        printf("PHASE16-BENCH ZEROCOPY-MIGRATION value=100B entries=500000 "
                        + "bytes=%dMB time=%.3fs MB/s=%.1f%n",
                result.bytesMb(), result.seconds(), result.mbPerSec());
        // 回归下限 20（全量套件 + Docker 后台负载波动，Phase 21 实测 25.9）；
        // 目标 >100MB/s 未达
        // （Phase 15 59.8 → Phase 16 ~80，如实记录）
        assertThat(result.mbPerSec()).isGreaterThan(20);
    }

    @Test
    void zeroCopyMigration1KB() throws Exception {
        MigrationResult result = run(1024, 150_000);
        printf("PHASE16-BENCH ZEROCOPY-MIGRATION value=1KB entries=150000 "
                        + "bytes=%dMB time=%.3fs MB/s=%.1f%n",
                result.bytesMb(), result.seconds(), result.mbPerSec());
        // 回归下限 60（全量套件 + Docker 后台负载波动，Phase 21 实测 75.5）；
        // Phase 17 并行迁移已达标
        assertThat(result.mbPerSec()).isGreaterThan(60);
    }

    @Test
    void zeroCopyMigration10KB() throws Exception {
        MigrationResult result = run(10_240, 20_000);
        printf("PHASE16-BENCH ZEROCOPY-MIGRATION value=10KB entries=20000 "
                        + "bytes=%dMB time=%.3fs MB/s=%.1f%n",
                result.bytesMb(), result.seconds(), result.mbPerSec());
        assertThat(result.mbPerSec()).isGreaterThan(150);
    }

    private MigrationResult run(int valueSize, int count) throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            byte[] value = new byte[valueSize];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < count; i++) {
                source.put(key(i), value);
            }
            int batchSize = BatchEncoder.batchSizeFor(valueSize + 16);
            // JIT 预热：小规模迁移（不计时），稳定测量
            MemTable warmSource = MemTable.create();
            MemTable warmTarget = MemTable.create();
            try {
                byte[] warmValue = new byte[valueSize];
                Arrays.fill(warmValue, (byte) 'w');
                for (int i = 0; i < 20_000; i++) {
                    warmSource.put(key(i), warmValue);
                }
                StreamingMigrator warm = new StreamingMigrator(warmSource, warmTarget,
                        new SlotTable(), dir.resolve("warm"), 0,
                        HashSlotRouter.SLOT_COUNT - 1, 1, Long.MAX_VALUE);
                while (!warm.runBatch(batchSize)) {
                    // warmup
                }
            } finally {
                warmSource.close();
                warmTarget.close();
            }
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir.resolve("measure"), 0,
                    HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            long start = System.nanoTime();
            while (!migrator.runBatch(batchSize)) {
                // stream
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            long totalBytes = (long) count * (16 + valueSize);
            assertThat(target.size()).isEqualTo(count);
            return new MigrationResult(
                    (int) (totalBytes / 1024 / 1024),
                    seconds,
                    totalBytes / 1024.0 / 1024.0 / seconds);
        } finally {
            source.close();
            target.close();
        }
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "zc:%08d", i)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record MigrationResult(int bytesMb, double seconds, double mbPerSec) {
    }
}
