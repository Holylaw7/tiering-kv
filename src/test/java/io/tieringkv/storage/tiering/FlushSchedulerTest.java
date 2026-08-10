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

class FlushSchedulerTest {

    @TempDir
    Path dir;

    @Test
    void backgroundFlushMovesEntriesAndRecordsMetrics() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        for (int i = 0; i < 100; i++) {
            memTable.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[16]);
        }
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1, "flush-test")) {
            FlushScheduler scheduler = new FlushScheduler(pool, memTable, wal, cold, metrics);
            assertThat(scheduler.scheduleFlush()).isTrue();
            assertThat(pool.awaitIdle(5000)).isTrue();
            assertThat(memTable.size()).isZero();
            assertThat(cold.get("k42".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(metrics.snapshot().flushCount()).isGreaterThanOrEqualTo(1);
            assertThat(metrics.snapshot().flushBytes()).isPositive();
        }
    }

    @Test
    void dedupWhileFlushInProgress() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal2"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold2")));
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        for (int i = 0; i < 50_000; i++) {
            memTable.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[16]);
        }
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1, "flush-test2")) {
            FlushScheduler scheduler = new FlushScheduler(pool, memTable, wal, cold, metrics);
            scheduler.scheduleFlush();
            boolean second = scheduler.scheduleFlush(); // 应被去重
            assertThat(pool.awaitIdle(30_000)).isTrue();
            assertThat(second).isFalse();
            assertThat(metrics.snapshot().flushCount()).isEqualTo(1);
        }
    }
}
