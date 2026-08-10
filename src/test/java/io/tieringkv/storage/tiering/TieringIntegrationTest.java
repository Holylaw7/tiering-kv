package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.HotnessTracker;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
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

/** 全链路：写超水位 → 自动 Flush / 异步迁移 → 冷层有数据 → 重启恢复。 */
class TieringIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void fullLoopSurvivesRestart() throws Exception {
        Path walDir = dir.resolve("wal");
        Path coldDir = dir.resolve("cold");
        Path migrationDir = dir.resolve("migration");
        WALConfig walConfig = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine.Config coldConfig = ColdStorageEngine.Config.defaults(coldDir);

        MemoryManager memory = new MemoryManager(3000);
        MemTable memTable = MemTable.createForTest(new MutableClock(0), memory);
        WALManager wal = new WALManager(walConfig);
        ColdStorageEngine cold = new ColdStorageEngine(coldConfig);
        TieringController.Config tieringConfig = new TieringController.Config(
                WatermarkManager.Config.defaults(), 1, 2, 3, 0, 5000, migrationDir);
        TieringController controller = new TieringController(
                tieringConfig, memory, memTable, wal, cold);
        EvictionManager eviction = new EvictionManager(
                memTable, memory, new LFUPolicy(new HotnessTracker(1000)),
                new ColdMigration(cold), wal, controller.migrationScheduler(),
                new MutableClock(0), 64);
        TrackingStorageEngine storage = new TrackingStorageEngine(
                new TieringStorageEngine(new io.tieringkv.storage.wal.WALStorageEngine(wal, memTable),
                        controller),
                eviction);

        for (int i = 0; i < 40; i++) {
            storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[200]);
        }
        assertThat(controller.flushPool().awaitIdle(20_000)).isTrue();
        assertThat(controller.migrationPool().awaitIdle(20_000)).isTrue();
        assertThat(cold.tablesSnapshot()).isNotEmpty();

        wal.close();
        cold.close();
        controller.close();

        // 重启：WAL 恢复 + 冷层加载 + 迁移日志恢复
        MemoryManager memory2 = new MemoryManager(1 << 30);
        MemTable memTable2 = MemTable.createForTest(new MutableClock(0), memory2);
        WALManager wal2 = new WALManager(walConfig);
        ColdStorageEngine cold2 = new ColdStorageEngine(coldConfig);
        TieringController controller2 = new TieringController(
                tieringConfig, memory2, memTable2, wal2, cold2);
        try (wal2; controller2) {
            wal2.recover(memTable2);
            controller2.recover();
            assertThat(controller2.flushPool().awaitIdle(10_000)).isTrue();
            assertThat(controller2.migrationPool().awaitIdle(10_000)).isTrue();

            for (int i = 0; i < 40; i++) {
                byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
                assertThat(memTable2.get(key) != null || cold2.get(key) != null)
                        .as("key k%d in memory or cold", i)
                        .isTrue();
            }
        }
    }
}
