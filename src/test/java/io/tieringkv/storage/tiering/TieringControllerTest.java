package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TieringControllerTest {

    @TempDir
    Path dir;

    @Test
    void writesTriggerAsyncFlushAndMetrics() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemoryManager memory = new MemoryManager(2000);
        MemTable memTable = MemTable.createForTest(new MutableClock(0), memory);
        WALManager wal = new WALManager(walConfig);
        TieringController.Config config = new TieringController.Config(
                WatermarkManager.Config.defaults(), 1, 2, 3, 0, 5000,
                dir.resolve("migration"));
        TieringController controller = new TieringController(config, memory, memTable, wal, cold);
        try (wal; controller) {
            TieringStorageEngine storage = new TieringStorageEngine(
                    new WALStorageEngine(wal, memTable), controller);
            for (int i = 0; i < 30; i++) {
                storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[200]);
            }
            assertThat(controller.flushPool().awaitIdle(10_000)).isTrue();

            assertThat(memTable.size()).isLessThan(30);
            assertThat(cold.tablesSnapshot()).isNotEmpty();
            StorageMetrics.Snapshot snapshot = controller.metrics().snapshot();
            assertThat(snapshot.flushCount()).isGreaterThanOrEqualTo(1);
            assertThat(snapshot.flushBytes()).isPositive();
            assertThat(snapshot.coldSstableCount()).isGreaterThanOrEqualTo(1);
            assertThat(snapshot.memoryMaxBytes()).isEqualTo(2000);
        }
    }
}
