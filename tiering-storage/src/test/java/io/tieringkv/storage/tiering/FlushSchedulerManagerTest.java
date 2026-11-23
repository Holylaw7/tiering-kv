package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTableManager;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** FlushScheduler 生产接入（ADR-0324）：manager 轮转 flush + 写入不停顿。 */
class FlushSchedulerManagerTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void scheduleFlushRotatesAndPersistsWithoutStoppingWrites()
            throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(
                        dir.resolve("cold")));
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1,
                     "flush-manager-test")) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            FlushScheduler scheduler = new FlushScheduler(
                    pool, manager, wal, cold, metrics);

            manager.put(bytes("k1"), bytes("v1"));
            manager.put(bytes("k2"), bytes("v2"));
            assertThat(scheduler.scheduleFlush()).isTrue();
            // flush 执行期间/之后：新写仍可进行（active 继续承接）
            manager.put(bytes("k3"), bytes("v3"));
            assertThat(pool.awaitIdle(10_000)).isTrue();

            // active 被轮转刷盘：k1/k2 必在冷层；k3 取决于写入与
            // rotate 的时序（内存或冷层至少一处，数据不丢）
            assertThat(cold.get(bytes("k1"))).isEqualTo(bytes("v1"));
            assertThat(cold.get(bytes("k2"))).isEqualTo(bytes("v2"));
            byte[] k3InMem = manager.get(bytes("k3"));
            byte[] k3InCold = cold.get(bytes("k3"));
            assertThat(k3InMem == null ? k3InCold : k3InMem)
                    .isEqualTo(bytes("v3"));
            assertThat(manager.immutableCount()).isZero();
            assertThat(metrics.snapshot().flushCount())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void repeatedFlushesCycleThroughGenerations() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal2"),
                1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(
                        dir.resolve("cold2")));
        StorageMetrics metrics = new StorageMetrics();
        try (WALManager wal = new WALManager(walConfig);
             TierWorkerPool pool = new TierWorkerPool(1,
                     "flush-cycle-test")) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            FlushScheduler scheduler = new FlushScheduler(
                    pool, manager, wal, cold, metrics);

            for (int gen = 1; gen <= 3; gen++) {
                manager.put(bytes("g" + gen), bytes("v" + gen));
                scheduler.scheduleFlush();
                assertThat(pool.awaitIdle(10_000)).isTrue();
            }
            for (int gen = 1; gen <= 3; gen++) {
                assertThat(cold.get(bytes("g" + gen)))
                        .isEqualTo(bytes("v" + gen));
            }
            assertThat(manager.immutableCount()).isZero();
        }
    }
}
