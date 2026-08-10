package io.tieringkv.benchmark.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.HotnessTracker;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.tiering.MigrationTask;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.tiering.WatermarkManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动调度基准（Phase 6）：自动 Flush 延迟/吞吐、异步迁移 100K/1M 的
 * ops/s 与 P99、内存压力下背压稳定性。
 */
@Tag("benchmark")
class TieringBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void automaticFlush() throws Exception {
        for (int count : new int[]{100_000, 200_000}) {
            Path sub = dir.resolve("flush-" + count);
            WALConfig walConfig = new WALConfig(sub.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
            ColdStorageEngine cold = new ColdStorageEngine(
                    ColdStorageEngine.Config.defaults(sub.resolve("cold")));
            MemTable memTable = MemTable.createForTest(
                    new MutableClock(0), new MemoryManager(1 << 30));
            for (int i = 0; i < count; i++) {
                memTable.put(key(i), new byte[16]);
            }
            try (WALManager wal = new WALManager(walConfig)) {
                long start = System.nanoTime();
                io.tieringkv.storage.cold.FlushManager.FlushStats stats =
                        io.tieringkv.storage.cold.FlushManager.flush(memTable, wal, cold);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                System.out.printf(Locale.ROOT,
                        "TIER-BENCH FLUSH entries=%d time=%.1fms throughput=%.0f entries/s%n",
                        stats.entriesFlushed(), seconds * 1000, stats.entriesFlushed() / seconds);
                assertThat(memTable.size()).isZero();
            }
        }
    }

    @Test
    void migrationThroughput() throws Exception {
        for (int count : new int[]{100_000, 1_000_000}) {
            Path sub = dir.resolve("migrate-" + count);
            WALConfig walConfig = new WALConfig(sub.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
            ColdStorageEngine cold = new ColdStorageEngine(
                    ColdStorageEngine.Config.defaults(sub.resolve("cold")));
            MemTable memTable = MemTable.createForTest(
                    new MutableClock(0), new MemoryManager(1 << 30));
            for (int i = 0; i < count; i++) {
                memTable.put(key(i), new byte[8]);
            }
            try (WALManager wal = new WALManager(walConfig);
                 io.tieringkv.storage.tiering.TierWorkerPool pool =
                         new io.tieringkv.storage.tiering.TierWorkerPool(2, "tier-bench-migration");
                 io.tieringkv.storage.tiering.MigrationLog log =
                         new io.tieringkv.storage.tiering.MigrationLog(sub.resolve("migration"))) {
                var metrics = new io.tieringkv.storage.tiering.StorageMetrics();
                var scheduler = new io.tieringkv.storage.tiering.MigrationScheduler(
                        pool, log, cold, wal, memTable, metrics, 3, 0);
                long[] latencies = new long[count];
                AtomicInteger index = new AtomicInteger();
                ConcurrentHashMap<ByteBuffer, Long> submittedAt = new ConcurrentHashMap<>();
                scheduler.setCompletionListener(task -> {
                    int i = index.getAndIncrement();
                    if (i < count) {
                        Long startNanos = submittedAt.remove(ByteBuffer.wrap(task.key()));
                        latencies[i] = startNanos == null
                                ? 0 : System.nanoTime() - startNanos;
                    }
                });
                long start = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    KeyValueEntry entry = memTable.getEntry(key(i));
                    submittedAt.put(ByteBuffer.wrap(entry.key()), System.nanoTime());
                    scheduler.submit(MigrationTask.pending(entry, "memory", "cold"));
                }
                assertThat(pool.awaitIdle(120_000)).isTrue();
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                Arrays.sort(latencies);
                double p99Ms = latencies[(int) (count * 0.99)] / 1_000_000.0;
                System.out.printf(Locale.ROOT,
                        "TIER-BENCH MIGRATE entries=%d throughput=%.0f ops/s success=%d p99=%.3fms%n",
                        count, count / seconds, metrics.snapshot().migrationSuccess(), p99Ms);
                assertThat(metrics.snapshot().migrationSuccess()).isEqualTo(count);
            }
        }
    }

    @Test
    void memoryPressureStability() throws Exception {
        Path sub = dir.resolve("pressure");
        WALConfig walConfig = new WALConfig(sub.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(sub.resolve("cold")));
        MemoryManager memory = new MemoryManager(2 << 20);
        MemTable memTable = MemTable.createForTest(new MutableClock(0), memory);
        WALManager wal = new WALManager(walConfig);
        TieringController.Config config = new TieringController.Config(
                WatermarkManager.Config.defaults(), 1, 2, 3, 0, 5000,
                sub.resolve("migration"));
        TieringController controller = new TieringController(config, memory, memTable, wal, cold);
        try (wal; controller) {
            EvictionManager eviction = new EvictionManager(
                    memTable, memory, new LFUPolicy(new HotnessTracker(1000)),
                    new ColdMigration(cold), wal, controller.migrationScheduler(),
                    new MutableClock(0), 64);
            TrackingStorageEngine storage = new TrackingStorageEngine(
                    new TieringStorageEngine(new WALStorageEngine(wal, memTable), controller),
                    eviction);
            long start = System.nanoTime();
            for (int i = 0; i < 20_000; i++) {
                storage.put(key(i), new byte[32]);
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            assertThat(controller.flushPool().awaitIdle(30_000)).isTrue();
            assertThat(controller.migrationPool().awaitIdle(30_000)).isTrue();
            System.out.printf(Locale.ROOT,
                    "TIER-BENCH PRESSURE writes=20000 time=%.1fs used=%d max=%d puts/s=%.0f%n",
                    seconds, memory.usedBytes(), memory.maxBytes(), 20_000 / seconds);
            assertThat(memory.usedBytes()).isLessThanOrEqualTo(memory.maxBytes() + 64_000);
        }
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "tier:%08d", i).getBytes(StandardCharsets.UTF_8);
    }
}
