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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 迁移队列增强（ADR-0325）：批量迁移 / 准入控制 / 动态 worker。 */
class MigrationSchedulerBatchTest {

    @TempDir
    Path dir;

    private MemTable memTable() {
        return MemTable.createForTest(new MutableClock(0),
                new MemoryManager(1 << 30));
    }

    private static MigrationTask task(MemTable memTable, String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        memTable.put(bytes, ("v-" + key)
                .getBytes(StandardCharsets.UTF_8));
        return MigrationTask.pending(memTable.getEntry(bytes),
                "memory", "cold");
    }

    @Test
    void batchMigratesToSingleColdTable() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemTable memTable = memTable();
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(2,
                     "batch-migration");
             MigrationLog log = new MigrationLog(
                     dir.resolve("migration"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, metrics, 3, 0,
                    0, 1, 4);
            List<MigrationTask> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tasks.add(task(memTable, "k" + i));
            }
            assertThat(scheduler.submitBatch(tasks)).isEqualTo(20);
            assertThat(pool.awaitIdle(10_000)).isTrue();

            for (int i = 0; i < 20; i++) {
                byte[] key = ("k" + i)
                        .getBytes(StandardCharsets.UTF_8);
                if (cold.get(key) == null) {
                    System.out.println("DIAG miss k" + i
                            + " tables=" + cold.tablesSnapshot().size()
                            + " success="
                            + metrics.snapshot().migrationSuccess()
                            + " failed="
                            + metrics.snapshot().migrationFailed()
                            + " mem=" + memTable.get(key));
                }
                assertThat(cold.get(key))
                        .isEqualTo(("v-k" + i)
                                .getBytes(StandardCharsets.UTF_8));
                assertThat(memTable.get(key)).isNull();
            }
            assertThat(metrics.snapshot().migrationSuccess())
                    .isEqualTo(20);
            assertThat(log.recover()).isEmpty();
            // 批量冷层写入应产生表（单表一次 flush）
            assertThat(cold.tablesSnapshot()).isNotEmpty();
        }
    }

    @Test
    void admissionRejectsBeyondMaxPending() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal2"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold2")));
        MemTable memTable = memTable();
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1,
                     "admission-test");
             MigrationLog log = new MigrationLog(
                     dir.resolve("migration2"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, metrics, 3, 0,
                    1, 1, 2);
            assertThat(scheduler.submit(task(memTable, "a"))).isTrue();
            assertThat(scheduler.submit(task(memTable, "b"))).isFalse();
            assertThat(scheduler.submitBatch(List.of(
                    task(memTable, "c"), task(memTable, "d"))))
                    .isZero();
            assertThat(pool.awaitIdle(10_000)).isTrue();
            assertThat(cold.get("a".getBytes(
                    StandardCharsets.UTF_8))).isNotNull();
            assertThat(cold.get("b".getBytes(
                    StandardCharsets.UTF_8))).isNull();
        }
    }

    @Test
    void dynamicWorkersScaleWithPending() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal3"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold3")));
        MemTable memTable = memTable();
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1,
                     "dynamic-test");
             MigrationLog log = new MigrationLog(
                     dir.resolve("migration3"))) {
            MigrationScheduler scheduler = new MigrationScheduler(
                    pool, log, cold, wal, memTable, metrics, 3, 0,
                    0, 1, 4);
            List<MigrationTask> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                tasks.add(task(memTable, "d" + i));
            }
            scheduler.submitBatch(tasks);
            // 高水位：worker 应扩展（提交瞬间 pending>8 → max 4）
            assertThat(pool.workers()).isGreaterThanOrEqualTo(4);
            assertThat(pool.awaitIdle(10_000)).isTrue();
            assertThat(metrics.snapshot().migrationSuccess())
                    .isEqualTo(30);
        }
    }
}
