package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** G0-5B-2 / ADR-0371：写路径不再复制 SSTable 清单。 */
class WritePathGaugeDecouplingTest {

    @TempDir
    Path dir;

    @Test
    void 写路径不调用tablesSnapshot() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemoryManager memory = new MemoryManager(1 << 20);
        MemTable memTable = MemTable.createForTest(new MutableClock(0), memory);
        WALManager wal = new WALManager(walConfig);
        TieringController.Config config = new TieringController.Config(
                WatermarkManager.Config.defaults(), 1, 2, 3, 0, 5000,
                dir.resolve("migration"));
        TieringController controller = new TieringController(config, memory, memTable, wal, cold);
        try (wal; controller) {
            long before = cold.tablesSnapshotCalls();
            for (int i = 0; i < 1_000; i++) {
                controller.onWriteCompleted();
            }
            long after = cold.tablesSnapshotCalls();
            assertThat(after - before)
                    .as("写路径不得复制表清单")
                    .isZero();
        }
    }

    @Test
    void 后台刷新会更新冷层快照() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir.resolve("cold"), 4096, 10, 1, 100));
        MemoryManager memory = new MemoryManager(1 << 20);
        MemTable memTable = MemTable.createForTest(new MutableClock(0), memory);
        WALManager wal = new WALManager(walConfig);
        TieringController.Config config = new TieringController.Config(
                WatermarkManager.Config.defaults(), 1, 2, 3, 0, 5000,
                dir.resolve("migration"));
        TieringController controller = new TieringController(config, memory, memTable, wal, cold);
        try (wal; controller) {
            cold.put(io.tieringkv.storage.memory.KeyValueEntry.live(
                    "k".getBytes(), "v".getBytes(), 0, -1, 1));
            controller.gaugeRefresher().requestRefresh();
            controller.gaugeRefresher().refreshOnce();
            assertThat(controller.metrics().snapshot().coldSstableCount()).isEqualTo(1);
        }
    }
}
