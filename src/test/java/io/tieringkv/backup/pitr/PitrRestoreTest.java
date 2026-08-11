package io.tieringkv.backup.pitr;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PITR 恢复闭环（ADR-0104）：快照 + 归档日志 → 任意时间点。 */
class PitrRestoreTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "key {0} value {1}")
    @ValueSource(ints = {0, 1, 256, 4096})
    void recordPayloadRoundTrip(int size) throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("log"));
        byte[] key = new byte[size];
        byte[] value = new byte[size + 1];
        log.append(new PitrRecord(0, 1, 10, key, value, false,
                "t1", "r1"));
        List<PitrRecord> records = log.readAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).hasSize(size);
        assertThat(records.get(0).value()).hasSize(size + 1);
        assertThat(records.get(0).txnId()).isEqualTo("t1");
        assertThat(records.get(0).regionId()).isEqualTo("r1");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100, 512, 600})
    void parameterizedAppendRead(int count) throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("log-" + count));
        for (int i = 0; i < count; i++) {
            log.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        List<PitrRecord> records = log.readAll();
        assertThat(records).hasSize(count);
        assertThat(records.get(count - 1).seq()).isEqualTo(count - 1);
    }

    @Test
    void rolloverCreatesMultipleSegments() throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("roll"), 10);
        for (int i = 0; i < 35; i++) {
            log.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        try (var stream = Files.list(dir.resolve("roll"))) {
            assertThat(stream.toList().size()).isGreaterThanOrEqualTo(4);
        }
        assertThat(log.readAll()).hasSize(35);
    }

    @Test
    void watermarkTracksLastSeq() throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("wm"));
        assertThat(log.watermark()).isEqualTo(-1);
        log.append(record(0, 1, 10, "k", "v", false));
        log.append(record(1, 2, 20, "k2", "v2", false));
        assertThat(log.watermark()).isEqualTo(1);
    }

    @Test
    void outOfOrderSeqRejected() throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("order"));
        log.append(record(0, 1, 10, "k", "v", false));
        assertThatThrownBy(() -> log.append(
                record(2, 1, 20, "k", "v", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crcCorruptionRejectedStrict() throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("crc"));
        log.append(record(0, 1, 10, "k", "v", false));
        Path segment = Files.list(dir.resolve("crc")).findFirst()
                .orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(segment, bytes);
        assertThatThrownBy(() -> PitrWriteLog.read(segment))
                .isInstanceOf(IOException.class);
    }

    @Test
    void tailTruncationTolerated() throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("trunc"));
        for (int i = 0; i < 5; i++) {
            log.append(record(i, i, i * 10, "k" + i, "v" + i, false));
        }
        Path segment = Files.list(dir.resolve("trunc")).findFirst()
                .orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        Files.write(segment, java.util.Arrays.copyOf(bytes,
                bytes.length - 7));
        assertThat(log.readAll().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void checkpointSaveLoadRoundTrip() throws Exception {
        Path ckptDir = dir.resolve("ckpt");
        byte[] snapshot = new byte[]{1, 2, 3, 4};
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                42, 1_000, snapshot));
        CheckpointManager.Checkpoint loaded =
                CheckpointManager.load(ckptDir);
        assertThat(loaded.watermark()).isEqualTo(42);
        assertThat(loaded.timestamp()).isEqualTo(1_000);
        assertThat(loaded.snapshotBytes()).isEqualTo(snapshot);
    }

    @ParameterizedTest(name = "watermark {0}")
    @ValueSource(longs = {-1, 0, 1_000_000})
    void parameterizedCheckpointWatermark(long watermark) throws Exception {
        Path ckptDir = dir.resolve("ckpt-" + watermark);
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                watermark, 0, new byte[0]));
        assertThat(CheckpointManager.load(ckptDir).watermark())
                .isEqualTo(watermark);
    }

    @Test
    void missingCheckpointFailsFast() {
        assertThatThrownBy(() -> CheckpointManager.load(
                dir.resolve("missing")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void corruptCheckpointRejected() throws Exception {
        Path ckptDir = dir.resolve("bad");
        Files.createDirectories(ckptDir);
        Files.write(ckptDir.resolve("checkpoint.bin"),
                new byte[]{0, 0, 0, 0, 1, 2, 3});
        assertThatThrownBy(() -> CheckpointManager.load(ckptDir))
                .isInstanceOf(IOException.class);
    }

    @Test
    void restoreToCheckpointTime() throws Exception {
        Path archiveDir = dir.resolve("arch");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v0"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), bytes("v1"), 2, 20, WriteType.PUT);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, 10);
        assertThat(restored.latestValue(bytes("k")))
                .isEqualTo(bytes("v0"));
    }

    @Test
    void restoreToLatestTime() throws Exception {
        Path archiveDir = dir.resolve("arch-latest");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v0"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-latest");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), bytes("v1"), 2, 20, WriteType.PUT);
        recorder.putVersion(bytes("k"), bytes("v2"), 3, 30, WriteType.PUT);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k")))
                .isEqualTo(bytes("v2"));
    }

    @Test
    void restoreToMiddleTime() throws Exception {
        Path archiveDir = dir.resolve("arch-mid");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v0"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-mid");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), bytes("v1"), 2, 20, WriteType.PUT);
        recorder.putVersion(bytes("k"), bytes("v2"), 3, 30, WriteType.PUT);
        recorder.putVersion(bytes("k"), bytes("v3"), 4, 40, WriteType.PUT);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, 30);
        assertThat(restored.latestValue(bytes("k")))
                .isEqualTo(bytes("v2"));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void parameterizedRestoreKeyCounts(int keyCount) throws Exception {
        Path archiveDir = dir.resolve("arch-keys-" + keyCount);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        for (int i = 0; i < keyCount; i++) {
            recorder.putVersion(bytes("k" + i), bytes("v" + i), i + 1,
                    (i + 1) * 10, WriteType.PUT);
        }
        Path ckptDir = dir.resolve("ckpt-keys-" + keyCount);
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 1_000,
                PersistentMvccIndex.snapshotBytes(engine)));
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k" + (keyCount - 1))))
                .isEqualTo(bytes("v" + (keyCount - 1)));
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {64, 4096, 65536, 262144})
    void parameterizedRestoreValueSizes(int size) throws Exception {
        Path archiveDir = dir.resolve("arch-value-" + size);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        byte[] value = new byte[size];
        recorder.putVersion(bytes("k"), value, 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-value-" + size);
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(value);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRestoreTxnCounts(int txnCount) throws Exception {
        Path archiveDir = dir.resolve("arch-txn-" + txnCount);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        for (int i = 0; i < txnCount; i++) {
            recorder.context("t" + i, "r" + (i % 2));
            recorder.putVersion(bytes("k" + i), bytes("v" + i), i + 1,
                    (i + 1) * 10, WriteType.PUT);
        }
        Path ckptDir = dir.resolve("ckpt-txn-" + txnCount);
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 1_000,
                PersistentMvccIndex.snapshotBytes(engine)));
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k" + (txnCount - 1))))
                .isEqualTo(bytes("v" + (txnCount - 1)));
    }

    @Test
    void deleteRestoredAsTombstone() throws Exception {
        Path archiveDir = dir.resolve("arch-del");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-del");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k"))).isNull();
    }

    @Test
    void deleteAfterTargetNotVisible() throws Exception {
        Path archiveDir = dir.resolve("arch-del-skip");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-del-skip");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, 15);
        assertThat(restored.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
    }

    @Test
    void doubleRestoreIdempotent() throws Exception {
        Path archiveDir = dir.resolve("arch-idem");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        recorder.putVersion(bytes("k"), bytes("v0"), 1, 10, WriteType.PUT);
        Path ckptDir = dir.resolve("ckpt-idem");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 10,
                PersistentMvccIndex.snapshotBytes(engine)));
        recorder.putVersion(bytes("k"), bytes("v1"), 2, 20, WriteType.PUT);
        MvccStorageEngine first = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        MvccStorageEngine second = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(first.latestValue(bytes("k")))
                .isEqualTo(bytes("v1"));
        assertThat(second.latestValue(bytes("k")))
                .isEqualTo(bytes("v1"));
    }

    @Test
    void emptyArchiveRestore() throws Exception {
        Path archiveDir = dir.resolve("arch-empty");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        Path ckptDir = dir.resolve("ckpt-empty");
        CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
                recorder.watermark(), 0,
                PersistentMvccIndex.snapshotBytes(engine)));
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckptDir, archiveDir, Long.MAX_VALUE);
        assertThat(restored.latestValue(bytes("k"))).isNull();
    }

    @Test
    void concurrentAppendReadConsistent() throws Exception {
        Path archiveDir = dir.resolve("conc");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
        int writers = 4;
        int perWriter = 25;
        List<Thread> threads = new java.util.ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        int offset = (int) (Math.random() * 10_000);
                        recorder.putVersion(bytes("k" + offset),
                                bytes("v" + offset), offset, offset * 10,
                                WriteType.PUT);
                    }
                } catch (IOException e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(failures.get()).isZero();
        assertThat(WALArchiveManager.open(archiveDir).readAll())
                .hasSize(writers * perWriter);
        assertThat(recorder.watermark())
                .isEqualTo(writers * perWriter - 1);
    }

    private static PitrRecord record(long seq, long startTS, long commitTS,
                                     String key, String value,
                                     boolean deleted) {
        return new PitrRecord(seq, startTS, commitTS,
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                value == null ? null
                        : value.getBytes(java.nio.charset.StandardCharsets
                        .UTF_8), deleted, "t" + seq, "r1");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
