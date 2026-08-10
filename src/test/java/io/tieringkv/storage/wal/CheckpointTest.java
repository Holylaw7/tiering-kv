package io.tieringkv.storage.wal;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void checkpointRestoresSnapshotThenReplaysRemainingWal() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            WALStorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
            storage.put("b".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
            wal.checkpoint(memTable);
            storage.put("c".getBytes(StandardCharsets.UTF_8), "3".getBytes(StandardCharsets.UTF_8));
            storage.delete("a".getBytes(StandardCharsets.UTF_8));
        }

        MemTable restored = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            CheckpointManager.Checkpoint checkpoint = wal.loadCheckpoint();
            assertThat(checkpoint).isNotNull();
            CheckpointManager.restore(restored, checkpoint);
            wal.recoverFrom(restored, checkpoint.segmentSequence(), checkpoint.offset());
        }

        assertThat(restored.get("a".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(restored.get("b".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("2".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("c".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void expiredSnapshotEntriesAreSkippedOnRestore() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        MutableClock clock = new MutableClock(0);
        MemTable memTable = MemTable.createForTest(clock, new MemoryManager(1 << 30));
        try (WALManager wal = new WALManager(config)) {
            WALStorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put("expired".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 50);
            storage.put("live".getBytes(StandardCharsets.UTF_8), "v".getBytes());
            wal.checkpoint(memTable);
        }

        MemTable restored = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            CheckpointManager.restore(restored, wal.loadCheckpoint());
        }
        // 快照条目过期点 = 50ms，恢复时（真实时钟）早已过期
        assertThat(restored.get("expired".getBytes())).isNull();
        assertThat(restored.get("live".getBytes())).isNotNull();
    }

    @Test
    void missingOrCorruptCheckpointReturnsNull() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            assertThat(wal.loadCheckpoint()).isNull();
        }
        Files.write(dir.resolve("checkpoint.bin"), new byte[]{1, 2, 3});
        try (WALManager wal = new WALManager(config)) {
            assertThat(wal.loadCheckpoint()).isNull();
        }
    }
}
