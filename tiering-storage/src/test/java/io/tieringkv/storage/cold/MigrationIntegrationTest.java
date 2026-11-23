package io.tieringkv.storage.cold;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.HotnessTracker;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 淘汰 → 冷迁移 → WAL DELETE → 崩溃恢复不复活（Phase 5 全链路）。 */
class MigrationIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void evictedEntriesLiveInColdAndDoNotResurrect() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        // pending 阈值 1：每次迁移立即落 SSTable，重启后冷层可见
        ColdStorageEngine.Config coldConfig = new ColdStorageEngine.Config(
                dir.resolve("cold"), 4096, 10, 1, 100);
        MutableClock clock = new MutableClock(0);
        MemoryManager memoryManager = new MemoryManager(150);
        MemTable memTable = MemTable.createForTest(clock, memoryManager);
        ColdStorageEngine cold = new ColdStorageEngine(coldConfig);

        try (WALManager wal = new WALManager(walConfig)) {
            EvictionManager evictionManager = new EvictionManager(
                    memTable, memoryManager, new LFUPolicy(new HotnessTracker(1000)),
                    new ColdMigration(cold), wal, clock, 64);
            TrackingStorageEngine storage = new TrackingStorageEngine(memTable, evictionManager, clock);
            for (int i = 0; i < 8; i++) {
                storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[32]);
            }

            assertThat(memTable.size()).isLessThan(8);
            assertThat(cold.tablesSnapshot()).isNotEmpty();
        }

        // 重启：WAL 重放 + 冷层加载
        MemTable recovered = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        WALConfig recoveredConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(recoveredConfig)) {
            wal.recover(recovered);
        }
        ColdStorageEngine coldRestarted = new ColdStorageEngine(coldConfig);
        assertThat(recovered.size()).isZero(); // 淘汰键已 DELETE，未复活
        assertThat(coldRestarted.tablesSnapshot()).isNotEmpty();
    }
}
