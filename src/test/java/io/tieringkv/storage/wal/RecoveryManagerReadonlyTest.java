package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 恢复只读语义（ADR-0342）：干净尾部不写打开，截断仅按需。 */
class RecoveryManagerReadonlyTest {

    @TempDir
    Path dir;

    @Test
    void cleanTailRecoveryDoesNotRequireWritePermission()
            throws Exception {
        WALConfig config = new WALConfig(dir, 1 << 20,
                WALConfig.FsyncPolicy.ALWAYS);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, bytes("a"), bytes("b"),
                    -1, 1L));
        }
        MemTable memTable = MemTable.create();
        RecoveryManager.RecoveryStats stats =
                new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(1);
        assertThat(memTable.get(bytes("a")))
                .isEqualTo(bytes("b"));
    }

    @Test
    void truncateTailOnlyWritesWhenNeeded() throws Exception {
        Path file = dir.resolve("tail.log");
        Files.write(file, new byte[16]);
        RecoveryManager.truncateTail(file, 16); // 干净尾部：no-op
        assertThat(Files.size(file)).isEqualTo(16);
        RecoveryManager.truncateTail(file, 8);  // 确有尾部：截断
        assertThat(Files.size(file)).isEqualTo(8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
