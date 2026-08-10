package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationQueueTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void asyncMigrationCompletesMove() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemTable memTable = newMemTable();
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        memTable.put(key, "v".getBytes(StandardCharsets.UTF_8));
        KeyValueEntry entry = memTable.getEntry(key);
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(2, "migration-test");
             MigrationLog log = new MigrationLog(dir.resolve("migration"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, metrics, 3, 0);
            assertThat(scheduler.submit(MigrationTask.pending(entry, "memory", "cold"))).isTrue();
            assertThat(pool.awaitIdle(5000)).isTrue();

            assertThat(cold.get(key)).isEqualTo("v".getBytes(StandardCharsets.UTF_8));
            assertThat(memTable.get(key)).isNull();
            assertThat(metrics.snapshot().migrationSuccess()).isEqualTo(1);
            assertThat(log.recover()).isEmpty(); // SUCCESS 已记录
        }
    }

    @Test
    void inFlightDedup() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal2"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold2")));
        MemTable memTable = newMemTable();
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        memTable.put(key, "v".getBytes(StandardCharsets.UTF_8));
        KeyValueEntry entry = memTable.getEntry(key);
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(2, "migration-test2");
             MigrationLog log = new MigrationLog(dir.resolve("migration2"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, new StorageMetrics(), 3, 0);
            assertThat(scheduler.submit(MigrationTask.pending(entry, "memory", "cold"))).isTrue();
            assertThat(scheduler.submit(MigrationTask.pending(entry, "memory", "cold"))).isFalse();
            assertThat(pool.awaitIdle(5000)).isTrue();
        }
    }

    @Test
    void failureKeepsMemoryCopyAndRecordsFailed() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal3"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold3")));
        MemTable memTable = newMemTable();
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        memTable.put(key, "v".getBytes(StandardCharsets.UTF_8));
        KeyValueEntry entry = memTable.getEntry(key);
        cold.close(); // 模拟冷层不可用
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(2, "migration-test3");
             MigrationLog log = new MigrationLog(dir.resolve("migration3"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, metrics, 3, 0);
            scheduler.submit(MigrationTask.pending(entry, "memory", "cold"));
            assertThat(pool.awaitIdle(5000)).isTrue();
            assertThat(memTable.get(key)).isNotNull(); // 内存保留
            assertThat(metrics.snapshot().migrationFailed()).isEqualTo(1);
            assertThat(log.recover()).isEmpty(); // FAILED 已记录
            assertThat(pool.activeCount()).isZero(); // worker 存活
        }
    }
}
