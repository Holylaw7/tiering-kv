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

/** 崩溃模拟三用例（ADR-0016）。 */
class CrashRecoveryTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void case1WalAndMemTableConsistent() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            WALStorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put("k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
            storage.put("k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));
            storage.delete("k1".getBytes(StandardCharsets.UTF_8));
        }

        MemTable recovered = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(recovered);
        assertThat(stats.recordsApplied()).isEqualTo(3);
        assertThat(recovered.get("k1".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(recovered.get("k2".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void case2WalExistsButMemTableEmpty() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
        }

        MemTable memTable = newMemTable(); // 空内存
        new RecoveryManager(config).recover(memTable);
        assertThat(memTable.get("a".getBytes())).isNotNull();
        assertThat(memTable.get("b".getBytes())).isNotNull();
    }

    @Test
    void case3CorruptLastRecordIgnored() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
        }
        Files.write(dir.resolve("000001.log"), new byte[]{9, 9, 9, 9, 9, 9, 9, 9},
                StandardOpenOption.APPEND);

        MemTable memTable = newMemTable();
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(2);
        assertThat(memTable.get("a".getBytes())).isNotNull();
        assertThat(memTable.get("b".getBytes())).isNotNull();
    }
}
