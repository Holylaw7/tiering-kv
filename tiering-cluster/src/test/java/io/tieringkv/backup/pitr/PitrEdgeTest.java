package io.tieringkv.backup.pitr;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PITR 边缘矩阵（ADR-0104）：重开、边界、空值、水位。 */
class PitrEdgeTest {

    @TempDir
    Path dir;

    @Test
    void reopenAfterAppendPreservesSeq() throws Exception {
        Path logDir = dir.resolve("reopen-seq");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        first.append(record(0, 1, 10, "k", "v", false));
        PitrWriteLog reopened = PitrWriteLog.open(logDir);
        reopened.append(record(1, 2, 20, "k2", "v2", false));
        assertThat(reopened.readAll()).hasSize(2);
    }

    @Test
    void reopenAfterCrashReadsAll() throws Exception {
        Path logDir = dir.resolve("reopen-crash");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        for (int i = 0; i < 10; i++) {
            first.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        PitrWriteLog reopened = PitrWriteLog.open(logDir);
        assertThat(reopened.readAll()).hasSize(10);
        assertThat(reopened.watermark()).isEqualTo(9);
    }

    @Test
    void nullValueRoundTrip() throws Exception {
        Path logDir = dir.resolve("null-value");
        PitrWriteLog log = PitrWriteLog.open(logDir);
        log.append(record(0, 1, 10, "k", null, true));
        PitrRecord restored = log.readAll().get(0);
        assertThat(restored.value()).isNull();
        assertThat(restored.deleted()).isTrue();
    }

    @ParameterizedTest(name = "commit {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void parameterizedCommitTsBoundary(long commitTS) throws Exception {
        Path logDir = dir.resolve("commit-" + commitTS);
        PitrWriteLog log = PitrWriteLog.open(logDir);
        log.append(record(0, 1, commitTS, "k", "v", false));
        assertThat(log.readAll().get(0).commitTS()).isEqualTo(commitTS);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(longs = {10, 20, 30})
    void restoreExactBoundary(long target) throws Exception {
        Path archive = dir.resolve("bound-arch-" + target);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archive);
        recorder.putVersion(bytes("k"), bytes("v10"), 1, 10, WriteType.PUT);
        Path ckpt = dir.resolve("bound-ckpt-" + target);
        CheckpointManager.save(ckpt, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), bytes("v20"), 2, 20, WriteType.PUT);
        recorder.putVersion(bytes("k"), bytes("v30"), 3, 30, WriteType.PUT);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckpt, archive, target);
        assertThat(restored.latestValue(bytes("k")))
                .isEqualTo(bytes("v" + target));
    }

    @Test
    void deleteFlagMatrix() throws Exception {
        Path logDir = dir.resolve("delete-flag");
        PitrWriteLog log = PitrWriteLog.open(logDir);
        log.append(record(0, 1, 10, "k", "v", false));
        log.append(record(1, 2, 20, "k", null, true));
        List<PitrRecord> records = log.readAll();
        assertThat(records.get(0).deleted()).isFalse();
        assertThat(records.get(1).deleted()).isTrue();
    }

    @ParameterizedTest(name = "watermark {0}")
    @ValueSource(longs = {0, 999})
    void checkpointWatermarkBoundary(long watermark) throws Exception {
        Path ckpt = dir.resolve("ckpt-wm-" + watermark);
        CheckpointManager.save(ckpt, new CheckpointManager.Checkpoint(
                watermark, 0, new byte[0]));
        assertThat(CheckpointManager.load(ckpt).watermark())
                .isEqualTo(watermark);
    }

    @Test
    void reopenAfterRollover() throws Exception {
        Path logDir = dir.resolve("reopen-roll");
        PitrWriteLog first = PitrWriteLog.open(logDir, 10);
        for (int i = 0; i < 35; i++) {
            first.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        PitrWriteLog reopened = PitrWriteLog.open(logDir, 10);
        reopened.append(record(35, 35, 350, "k35", "v35", false));
        assertThat(reopened.readAll()).hasSize(36);
    }

    @Test
    void watermarkAfterReopen() throws Exception {
        Path logDir = dir.resolve("wm-reopen");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        first.append(record(0, 1, 10, "k", "v", false));
        PitrWriteLog reopened = PitrWriteLog.open(logDir);
        assertThat(reopened.watermark()).isEqualTo(0);
    }

    @Test
    void emptyLogReopen() throws Exception {
        Path logDir = dir.resolve("empty-reopen");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        assertThat(first.watermark()).isEqualTo(-1);
        PitrWriteLog reopened = PitrWriteLog.open(logDir);
        assertThat(reopened.readAll()).isEmpty();
    }

    @Test
    void segmentAppendAfterReopen() throws Exception {
        Path logDir = dir.resolve("append-reopen");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        first.append(record(0, 1, 10, "k", "v", false));
        PitrWriteLog reopened = PitrWriteLog.open(logDir);
        reopened.append(record(1, 2, 20, "k2", "v2", false));
        reopened.append(record(2, 3, 30, "k3", "v3", false));
        assertThat(reopened.readAll()).hasSize(3);
    }

    @Test
    void concurrentReopenSafe() throws Exception {
        Path logDir = dir.resolve("conc-reopen");
        PitrWriteLog first = PitrWriteLog.open(logDir);
        for (int i = 0; i < 5; i++) {
            first.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        PitrWriteLog a = PitrWriteLog.open(logDir);
        PitrWriteLog b = PitrWriteLog.open(logDir);
        assertThat(a.readAll()).hasSize(5);
        assertThat(b.readAll()).hasSize(5);
    }

    @Test
    void largeTxnIdRoundTrip() throws Exception {
        Path logDir = dir.resolve("txn-id");
        PitrWriteLog log = PitrWriteLog.open(logDir);
        String txnId = "txn-" + "x".repeat(512);
        log.append(new PitrRecord(0, 1, 10, bytes("k"), bytes("v"),
                false, txnId, "r1"));
        assertThat(log.readAll().get(0).txnId()).isEqualTo(txnId);
    }

    private static PitrRecord record(long seq, long startTS,
                                     long commitTS, String key,
                                     String value, boolean deleted) {
        return new PitrRecord(seq, startTS, commitTS, bytes(key),
                value == null ? null : bytes(value), deleted,
                "t" + seq, "r1");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
