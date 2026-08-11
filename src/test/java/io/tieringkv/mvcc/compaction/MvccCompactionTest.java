package io.tieringkv.mvcc.compaction;

import io.tieringkv.mvcc.MvccMetricsRegistry;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.gc.GcConfig;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** MVCC 在线压缩（ADR-0085）：合并版本、SafePoint 保留、索引文件产物。 */
class MvccCompactionTest {

    @TempDir
    Path dir;

    @Test
    void compactMergesOldVersions() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 10; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        MvccCompactor.CompactionResult result = compactor.compact();
        assertThat(result.collectedVersions()).isEqualTo(9);
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v10"));
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactRespectsSafePoint() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(25));
        compactor.compact();
        // 保留 commitTS >= 25 的版本（v3..v5）+ 最新
        assertThat(engine.versions(bytes("k"))).hasSize(3);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactWritesIndexFileAtomically() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 6; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        Path index = dir.resolve("mvcc.index");
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT, index,
                new MvccMetricsRegistry());
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        assertThat(Files.exists(index)).isTrue();
        assertThat(Files.exists(index.resolveSibling(
                index.getFileName() + ".tmp"))).isFalse();
        // 压缩后的索引文件可加载且保留最新值
        assertThat(PersistentMvccIndex.load(index).versions()).hasSize(1);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactIdempotent() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        assertThat(compactor.compact().collectedVersions()).isZero();
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactEmptyEngineNoop() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        assertThat(compactor.compact().collectedVersions()).isZero();
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactPreservesLatestDeleteTombstone() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 3; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        engine.putVersion(bytes("k"), null, 4, 40, WriteType.DELETE);
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        assertThat(engine.latestValue(bytes("k"))).isNull();
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactSkipsLockRecords() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("provisional"), 7, 7,
                WriteType.LOCK);
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        compactor.compact();
        assertThat(engine.versionCount()).isEqualTo(1);
        assertThat(engine.versions(bytes("k")).get(0).writeType())
                .isEqualTo(WriteType.LOCK);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactRecordsMetrics() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 8; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccMetricsRegistry metrics = new MvccMetricsRegistry();
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT, null, metrics);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        assertThat(metrics.snapshot().compactionVersions()).isEqualTo(7);
        assertThat(metrics.snapshot().compactionBytes()).isGreaterThan(0);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void backgroundScheduledCompactionRuns() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 6; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.start(10);
        Thread.sleep(300);
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactedIndexRestoresEngine() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 6; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        Path index = dir.resolve("restore.index");
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT, index, null);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        compactor.close();
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                index, MemTable.create());
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v6"));
        assertThat(restored.versions(bytes("k"))).hasSize(1);
        ((MemTable) restored.underlying()).close();
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "safePoint {0}")
    @ValueSource(longs = {15, 25, 35, 45, 55})
    void parameterizedSafePointRetention(long safePoint) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(safePoint));
        compactor.compact();
        int retained = engine.versions(bytes("k")).size();
        // 最新版本无条件保留；其余按 safePoint 回收
        assertThat(retained).isEqualTo(
                Math.max(1, 5 - (safePoint - 1) / 10));
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v5"));
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactAfterManualGcNoop() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(engine,
                        GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        gc.close();
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        assertThat(compactor.compact().collectedVersions()).isZero();
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void compactorCloseStopsScheduler() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.start(5);
        compactor.close();
        compactor.close(); // 幂等
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {3, 5, 8, 12, 20})
    void parameterizedVersionCount(int versionCount) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= versionCount; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        MvccCompactor.CompactionResult result = compactor.compact();
        assertThat(result.collectedVersions()).isEqualTo(versionCount - 1);
        assertThat(engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v" + versionCount));
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {10, 100, 500})
    void parameterizedKeyCount(int keyCount) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < keyCount; i++) {
            for (int v = 1; v <= 8; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        compactor.compact();
        assertThat(engine.versionCount()).isEqualTo(keyCount);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
