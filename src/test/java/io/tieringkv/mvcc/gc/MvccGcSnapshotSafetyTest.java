package io.tieringkv.mvcc.gc;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccGcManager;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.PrewriteExecutor;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 快照安全（ADR-0078）：活跃快照、LOCK、最新版本不得被批量 GC 回收。 */
class MvccGcSnapshotSafetyTest {

    @Test
    void activeSnapshotVersionNeverCollected() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 10; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        // 活跃快照 readTS=55：safePoint 必须 <= 55，否则 v5(50) 不可删
        gc.updateSafePoint(new SafePoint(50));
        gc.gc();
        assertThat(engine.read(bytes("k"), 55).value()).isEqualTo(bytes("v5"));
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void latestVersionAlwaysPreserved() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 8; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        gc.gc();
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v8"));
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void lockRecordsNeverGcCollected() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        // 构造一个旧 LOCK provisional 记录（GC 不负责清理）
        PrewriteExecutor prewrite = new PrewriteExecutor();
        prewrite.prewrite(engine, locks, bytes("k"), bytes("v"), false,
                "txn-1", bytes("k"), 5, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        gc.gc();
        // LOCK provisional 保留（回滚/恢复负责），且对读不可见
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.versions(bytes("k")).get(0).writeType())
                .isEqualTo(WriteType.LOCK);
        assertThat(engine.latestValue(bytes("k"))).isNull();
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void deleteTombstoneAsLatestSurvivesGc() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 3; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        engine.putVersion(bytes("k"), null, 4, 40, WriteType.DELETE);
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(engine.latestValue(bytes("k"))).isNull();
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void snapshotReaderUnaffectedAfterBatchGc() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 20; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(50));
        gc.gc();
        SnapshotReader reader = new SnapshotReader();
        assertThat(reader.get(engine, bytes("k"), 50)).isEqualTo(bytes("v5"));
        assertThat(reader.get(engine, bytes("k"), 80)).isEqualTo(bytes("v8"));
        assertThat(reader.get(engine, bytes("k"), Long.MAX_VALUE))
                .isEqualTo(bytes("v20"));
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "safePoint {0}")
    @ValueSource(longs = {15, 25, 35, 45})
    void parameterizedSafePointRetention(long safePoint) {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(safePoint));
        gc.gc();
        int retained = engine.versions(bytes("k")).size();
        assertThat(retained).isEqualTo(5 - (safePoint - 1) / 10);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v5"));
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void rollbackPointerSurvivesGc() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        // GC 后版本链仍有序：回滚/读取依赖的 commitTS 顺序不被破坏
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.versions(bytes("k")).get(0).commitTS()).isEqualTo(20);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void mixedPutDeleteVersionsRetainedBySafePoint() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        engine.putVersion(bytes("k"), bytes("v3"), 3, 30, WriteType.PUT);
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(25));
        gc.gc();
        // v1(10)、v2(20) 均 < safePoint 且非最新 → 回收，仅保留 v3(30)
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v3"));
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void noSafePointCollectsNothing() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 4; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        assertThat(gc.gc().collectedVersions()).isZero();
        assertThat(engine.versionCount()).isEqualTo(4);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void emptyEngineGcNoop() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        assertThat(gc.gc().collectedVersions()).isZero();
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void lockOnlyEngineUntouched() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        new PrewriteExecutor().prewrite(engine, locks, bytes("k"), bytes("v"),
                false, "txn", bytes("k"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        gc.gc();
        assertThat(engine.versionCount()).isEqualTo(1);
        assertThat(locks.size()).isEqualTo(1);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void safePointBelowFirstVersionCollectsNothing() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 3; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(5));
        assertThat(gc.gc().collectedVersions()).isZero();
        assertThat(engine.versionCount()).isEqualTo(3);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void oldTombstoneCollectableLatestPutPreserved() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        engine.putVersion(bytes("k"), bytes("v3"), 3, 30, WriteType.PUT);
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v3"));
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void manyKeysLatestPreservedAfterGc() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 500; i++) {
            for (int v = 1; v <= 8; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v), v, v * 10,
                        WriteType.PUT);
            }
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        for (int i = 0; i < 500; i++) {
            assertThat(engine.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v8"));
        }
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void deleteVersionThenGcIdempotent() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(gc.gc().collectedVersions()).isZero();
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
