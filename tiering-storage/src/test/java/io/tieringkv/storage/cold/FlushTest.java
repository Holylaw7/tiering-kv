package io.tieringkv.storage.cold;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
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

class FlushTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void flushMovesEntriesToColdAndCheckpointsWal() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(walConfig)) {
            StorageEngine storage = new WALStorageEngine(wal, memTable);
            for (int i = 0; i < 100; i++) {
                storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), ("v" + i).getBytes());
            }
            FlushManager.FlushStats stats = FlushManager.flush(memTable, wal, cold);
            assertThat(stats.entriesFlushed()).isEqualTo(100);
            assertThat(memTable.size()).isZero();
            assertThat(cold.get("k42".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("v42".getBytes(StandardCharsets.UTF_8));
            assertThat(wal.loadCheckpoint()).isNotNull();
        }

        // 重启（旧 WAL 已关闭落盘）：WAL 快照为空 + 冷层 Manifest 恢复数据
        MemTable recovered = newMemTable();
        ColdStorageEngine coldRestarted = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        try (WALManager walAgain = new WALManager(walConfig)) {
            walAgain.recoverFrom(recovered,
                    walAgain.loadCheckpoint().segmentSequence(),
                    walAgain.loadCheckpoint().offset());
        }
        assertThat(recovered.size()).isZero();
        assertThat(coldRestarted.get("k42".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v42".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void versionGuardKeepsConcurrentNewerWrite() {
        MemTable memTable = newMemTable();
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        memTable.put(key, "v1".getBytes());
        assertThat(memTable.removePhysicalIfVersion(key, 1)).isTrue();

        memTable.put(key, "v2".getBytes());
        assertThat(memTable.removePhysicalIfVersion(key, 1)).isFalse(); // 旧版本守卫拒绝
        assertThat(memTable.get(key)).isEqualTo("v2".getBytes());
    }

    @Test
    void flushTwiceWritesNewestValue() throws Exception {
        WALConfig walConfig = new WALConfig(dir.resolve("wal2"), 1 << 20, WALConfig.FsyncPolicy.NO);
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir.resolve("cold2")));
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(walConfig)) {
            StorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put("k".getBytes(StandardCharsets.UTF_8), "old".getBytes());
            FlushManager.flush(memTable, wal, cold);
            storage.put("k".getBytes(StandardCharsets.UTF_8), "new".getBytes());
            FlushManager.flush(memTable, wal, cold);
            assertThat(cold.get("k".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("new".getBytes(StandardCharsets.UTF_8));
            assertThat(memTable.size()).isZero();
        }
    }
}
