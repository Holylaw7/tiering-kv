package io.tieringkv.mvcc.index;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 持久化 MVCC 索引（ADR-0080）：round-trip / 校验 / 恢复 / 增量重建。 */
class MvccIndexPersistenceTest {

    @TempDir
    Path dir;

    @Test
    void saveLoadRoundTrip() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions()).hasSize(12);
        assertThat(loaded.maxCommitTS()).isEqualTo(69);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void restoreKeepsFullHistory() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MemTable storage = MemTable.create();
        MvccStorageEngine restored =
                PersistentMvccIndex.restore(file, storage);
        assertThat(restored.versions(bytes("k1"))).hasSize(5);
        assertThat(restored.versions(bytes("k2"))).hasSize(4);
        assertThat(restored.versions(bytes("k3"))).hasSize(3);
        storage.close();
    }

    @Test
    void restorePreservesValues() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, MemTable.create());
        assertThat(restored.latestValue(bytes("k1"))).isEqualTo(bytes("k1-v5"));
        assertThat(restored.latestValue(bytes("k2"))).isEqualTo(bytes("k2-v4"));
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void restorePreservesDeleteTombstone() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, MemTable.create());
        assertThat(restored.latestValue(bytes("k"))).isNull();
        assertThat(restored.versions(bytes("k"))).hasSize(2);
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void restorePreservesLockRecords() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("provisional"), 7, 7,
                WriteType.LOCK);
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, MemTable.create());
        assertThat(restored.versions(bytes("k")).get(0).writeType())
                .isEqualTo(WriteType.LOCK);
        assertThat(restored.latestValue(bytes("k"))).isNull();
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void restoreSupportsHistoricalReads() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, MemTable.create());
        SnapshotReader reader = new SnapshotReader();
        assertThat(reader.get(restored, bytes("k1"), 30))
                .isEqualTo(bytes("k1-v3"));
        assertThat(reader.get(restored, bytes("k1"), Long.MAX_VALUE))
                .isEqualTo(bytes("k1-v5"));
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void incrementalRebuildAddsNewerVersions() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 3; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        // 快照后继续写入（等价 WAL replay 的后续记录）
        engine.putVersion(bytes("k"), bytes("v4"), 4, 40, WriteType.PUT);
        engine.putVersion(bytes("k2"), bytes("new"), 5, 50, WriteType.PUT);
        MemTable storage = (MemTable) engine.underlying();

        MvccStorageEngine rebuilt =
                PersistentMvccIndex.restoreIncremental(file, storage);
        assertThat(rebuilt.versions(bytes("k"))).hasSize(4);
        assertThat(rebuilt.versions(bytes("k2"))).hasSize(1);
        assertThat(rebuilt.latestValue(bytes("k"))).isEqualTo(bytes("v4"));
        storage.close();
    }

    @Test
    void incrementalRebuildSkipsSnapshotVersions() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MemTable storage = (MemTable) engine.underlying();

        MvccStorageEngine rebuilt =
                PersistentMvccIndex.restoreIncremental(file, storage);
        // 快照内版本不重复追加
        assertThat(rebuilt.versions(bytes("k"))).hasSize(5);
        storage.close();
    }

    @Test
    void incrementalRebuildMatchesFullScan() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        engine.putVersion(bytes("k9"), bytes("late"), 9, 90, WriteType.PUT);
        MemTable storage = (MemTable) engine.underlying();

        MvccStorageEngine rebuilt =
                PersistentMvccIndex.restoreIncremental(file, storage);
        MvccStorageEngine full = new MvccStorageEngine(storage);
        assertThat(rebuilt.versionCount()).isEqualTo(full.versionCount());
        assertThat(rebuilt.latestValue(bytes("k9"))).isEqualTo(bytes("late"));
        storage.close();
    }

    @Test
    void crcCorruptionDetected() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        byte[] data = Files.readAllBytes(file);
        data[data.length - 5] ^= 0x55; // 破坏 payload，保留 CRC 字段
        Files.write(file, data);
        assertThatThrownBy(() -> PersistentMvccIndex.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("crc");
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void magicCorruptionDetected() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        byte[] data = Files.readAllBytes(file);
        data[0] ^= 0x01;
        Files.write(file, data);
        assertThatThrownBy(() -> PersistentMvccIndex.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("magic");
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void truncatedFileDetected() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        byte[] data = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(data, 8));
        assertThatThrownBy(() -> PersistentMvccIndex.load(file))
                .isInstanceOf(IOException.class);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> PersistentMvccIndex.load(
                dir.resolve("missing.bin"))).isInstanceOf(IOException.class);
    }

    @Test
    void emptySnapshotRoundTrip() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        Path file = dir.resolve("empty.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions()).isEmpty();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void multipleKeysRoundTrip() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("multi.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions()).hasSize(12);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void snapshotAfterGcRestoresCleanState() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(engine,
                        io.tieringkv.mvcc.gc.GcConfig.DEFAULT);
        gc.updateSafePoint(new io.tieringkv.mvcc.SafePoint(100));
        gc.gc();
        gc.close();
        Path file = dir.resolve("gc.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, MemTable.create());
        assertThat(restored.versions(bytes("k"))).hasSize(1);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v5"));
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void saveOverwritesExistingFile() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        engine.putVersion(bytes("extra"), bytes("v"), 1, 1, WriteType.PUT);
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        assertThat(PersistentMvccIndex.load(file).versions()).hasSize(13);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void largeDatasetRoundTrip() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 5_000; i++) {
            for (int v = 1; v <= 10; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        Path file = dir.resolve("large.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions()).hasSize(50_000);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void fromIndexSkipsStorageScan() {
        MemTable storage = MemTable.create();
        storage.put(bytes("raw"), bytes("value"));
        MvccStorageEngine engine = MvccStorageEngine.fromIndex(storage,
                java.util.List.of());
        // 索引为空：读取走索引，底层原始键不可见
        assertThat(engine.latestValue(bytes("raw"))).isNull();
        assertThat(engine.versionCount()).isZero();
        storage.close();
    }

    @Test
    void snapshotMaxCommitTSComputed() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 30, WriteType.PUT);
        MvccIndexSnapshot snapshot = PersistentMvccIndex.snapshot(engine);
        assertThat(snapshot.maxCommitTS()).isEqualTo(30);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void restoredEngineAcceptsNewWrites() throws Exception {
        MvccStorageEngine engine = engineWithHistory();
        Path file = dir.resolve("index.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        ((MemTable) engine.underlying()).close();

        MemTable storage = MemTable.create();
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                file, storage);
        restored.putVersion(bytes("new"), bytes("v"), 200, 201, WriteType.PUT);
        assertThat(restored.latestValue(bytes("new"))).isEqualTo(bytes("v"));
        assertThat(restored.latestValue(bytes("k1"))).isEqualTo(bytes("k1-v5"));
        storage.close();
    }

    @Test
    void mixedTypesRoundTrip() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("a"), bytes("v"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("b"), null, 2, 20, WriteType.DELETE);
        engine.putVersion(bytes("c"), bytes("p"), 3, 3, WriteType.LOCK);
        Path file = dir.resolve("mixed.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions()).extracting(
                io.tieringkv.mvcc.MvccEntry::writeType)
                .containsExactlyInAnyOrder(WriteType.PUT, WriteType.DELETE,
                        WriteType.LOCK);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void restoreIncrementalEmptySnapshotScansAll() throws Exception {
        MemTable storage = MemTable.create();
        MvccStorageEngine engine = new MvccStorageEngine(storage);
        Path file = dir.resolve("empty.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        MvccStorageEngine rebuilt =
                PersistentMvccIndex.restoreIncremental(file, storage);
        assertThat(rebuilt.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        storage.close();
    }

    @Test
    void unsupportedVersionDetected() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        Path file = dir.resolve("version.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        byte[] data = Files.readAllBytes(file);
        // MAGIC(7) 后第一个 int 是版本号，改为 99
        data[7] = 0;
        data[8] = 0;
        data[9] = 0;
        data[10] = 99;
        Files.write(file, data);
        assertThatThrownBy(() -> PersistentMvccIndex.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version");
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void nullValueRecordRoundTrip() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        Path file = dir.resolve("null.bin");
        PersistentMvccIndex.save(file, PersistentMvccIndex.snapshot(engine));
        MvccIndexSnapshot loaded = PersistentMvccIndex.load(file);
        assertThat(loaded.versions().get(0).value()).isNull();
        assertThat(loaded.versions().get(0).writeType())
                .isEqualTo(WriteType.DELETE);
        ((MemTable) engine.underlying()).close();
    }

    private static MvccStorageEngine engineWithHistory() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k1"), bytes("k1-v" + v), v, v * 10,
                    WriteType.PUT);
        }
        for (int v = 1; v <= 4; v++) {
            engine.putVersion(bytes("k2"), bytes("k2-v" + v), v + 10,
                    (v + 10) * 2, WriteType.PUT);
        }
        for (int v = 1; v <= 3; v++) {
            engine.putVersion(bytes("k3"), bytes("k3-v" + v), v + 20,
                    (v + 20) * 3, WriteType.PUT);
        }
        return engine;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
