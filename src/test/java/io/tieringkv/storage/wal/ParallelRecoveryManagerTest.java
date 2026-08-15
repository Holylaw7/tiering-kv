package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 并行崩溃恢复（ADR-0329）：多段一致 / 等价串行 / 损坏截断。 */
class ParallelRecoveryManagerTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private WALManager wal() throws Exception {
        return new WALManager(new WALConfig(dir.resolve("wal"),
                1 << 20, WALConfig.FsyncPolicy.NO));
    }

    @Test
    void multiSegmentRecoveryMatchesSerial() throws Exception {
        try (WALManager wal = wal()) {
            for (int gen = 0; gen < 4; gen++) {
                for (int i = 0; i < 20; i++) {
                    wal.append(WALEntry.put(gen * 100L + i,
                            bytes("k" + gen + "-" + i),
                            bytes("v" + gen + "-" + i), -1, i));
                }
                if (gen < 3) {
                    wal.rotate();
                }
            }
        }

        MemTable serial = MemTable.create();
        RecoveryManager.RecoveryStats serialStats =
                new RecoveryManager(new WALConfig(dir.resolve("wal"),
                        1 << 20, WALConfig.FsyncPolicy.NO))
                        .recover(serial);
        assertThat(serialStats.segmentsReplayed()).isEqualTo(4);

        MemTable parallel = MemTable.create();
        ParallelRecoveryManager parallelManager =
                new ParallelRecoveryManager(new WALConfig(
                        dir.resolve("wal"), 1 << 20,
                        WALConfig.FsyncPolicy.NO), 4);
        RecoveryManager.RecoveryStats parallelStats =
                parallelManager.recover(parallel);
        assertThat(parallelStats.segmentsReplayed()).isEqualTo(4);
        assertThat(parallelStats.recordsApplied()).isEqualTo(80);

        for (int gen = 0; gen < 4; gen++) {
            for (int i = 0; i < 20; i++) {
                byte[] key = bytes("k" + gen + "-" + i);
                assertThat(parallel.get(key))
                        .isEqualTo(serial.get(key));
            }
        }
    }

    @Test
    void corruptedSegmentTruncatesAndStops() throws Exception {
        try (WALManager wal = wal()) {
            for (int gen = 0; gen < 3; gen++) {
                wal.append(WALEntry.put(gen, bytes("k" + gen),
                        bytes("v" + gen), -1, gen));
                wal.rotate();
            }
        }
        // 破坏中间段（000002.log）
        Path mid = dir.resolve("wal").resolve("000002.log");
        byte[] data = Files.readAllBytes(mid);
        data[data.length / 2] ^= 0x7F;
        Files.write(mid, data);

        MemTable recovered = MemTable.create();
        RecoveryManager.RecoveryStats stats =
                new ParallelRecoveryManager(new WALConfig(
                        dir.resolve("wal"), 1 << 20,
                        WALConfig.FsyncPolicy.NO), 4)
                        .recover(recovered);
        assertThat(stats.corruptedRecordsDiscarded()).isEqualTo(1);
        // 段 1 应用；段 2 损坏（内容丢弃 + 截断），后续段停止
        assertThat(recovered.get(bytes("k0"))).isEqualTo(bytes("v0"));
        assertThat(recovered.get(bytes("k1"))).isNull();
        assertThat(stats.segmentsReplayed()).isEqualTo(1);
    }

    @Test
    void singleSegmentFallsBackToSerial() throws Exception {
        try (WALManager wal = wal()) {
            for (int i = 0; i < 10; i++) {
                wal.append(WALEntry.put(i, bytes("k" + i),
                        bytes("v" + i), -1, i));
            }
        }
        MemTable recovered = MemTable.create();
        RecoveryManager.RecoveryStats stats =
                new ParallelRecoveryManager(new WALConfig(
                        dir.resolve("wal"), 1 << 20,
                        WALConfig.FsyncPolicy.NO))
                        .recover(recovered);
        assertThat(stats.recordsApplied()).isEqualTo(10);
        assertThat(recovered.get(bytes("k9"))).isEqualTo(bytes("v9"));
    }
}
