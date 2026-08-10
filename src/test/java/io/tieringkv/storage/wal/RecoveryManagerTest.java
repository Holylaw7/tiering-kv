package io.tieringkv.storage.wal;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryManagerTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void replaysAllValidRecords() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
            wal.append(WALEntry.delete(3L, "a".getBytes(), 3L));
        }
        MemTable memTable = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(3);
        assertThat(memTable.get("a".getBytes())).isNull();
        assertThat(memTable.get("b".getBytes())).isNotNull();
    }

    @Test
    void expiredPutIsNotResurrected() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            // timestamp = 0 + ttl 50 → 绝对过期点 50ms，恢复时早已过期
            wal.append(WALEntry.put(0L, "expired".getBytes(), "v".getBytes(), 50, 1L));
            wal.append(WALEntry.put(0L, "live".getBytes(), "v".getBytes(), -1, 2L));
        }
        MemTable memTable = newMemTable();
        new RecoveryManager(config).recover(memTable);
        assertThat(memTable.get("expired".getBytes())).isNull();
        assertThat(memTable.get("live".getBytes())).isNotNull();
    }

    @Test
    void partialTailIsDiscardedAndTruncated() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
        }
        Path segment = dir.resolve("000001.log");
        long originalSize = Files.size(segment);
        Files.write(segment, new byte[]{1, 2, 3, 4, 5}, StandardOpenOption.APPEND);

        MemTable memTable = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(2);
        assertThat(Files.size(segment)).isEqualTo(originalSize); // 已截断
    }

    @Test
    void checksumFailureStopsAtCorruptRecord() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
            wal.append(WALEntry.put(3L, "c".getBytes(), "3".getBytes(), -1, 3L));
        }
        Path segment = dir.resolve("000001.log");
        byte[] bytes = Files.readAllBytes(segment);
        int firstLength = WALRecord.HEADER_SIZE + 1 + 1 + WALRecord.CHECKSUM_SIZE;
        bytes[firstLength + 10] ^= 0x01; // 损坏第二条记录
        Files.write(segment, bytes);

        MemTable memTable = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(1);
        assertThat(stats.corruptedRecordsDiscarded()).isEqualTo(1);
        assertThat(memTable.get("a".getBytes())).isNotNull();
        assertThat(memTable.get("b".getBytes())).isNull();
        assertThat(memTable.get("c".getBytes())).isNull();
    }

    @Test
    void emptyWalRecoversNothing() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager ignored = new WALManager(config)) {
            // 空 WAL
        }
        MemTable memTable = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isZero();
        assertThat(memTable.size()).isZero();
    }
}
