package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** MVCC GC（ADR-0075）：安全点 / 保留最新 / 活跃快照保护。 */
class MvccGcTest {

    @Test
    void keepsLatestVersion() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 5; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(100));
        MvccGcManager.GcResult result = gc.gc();
        assertThat(result.collectedVersions()).isEqualTo(4);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v5"));
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        gc.close();
    }

    @Test
    void safePointNoneCollectsNothing() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        MvccGcManager gc = new MvccGcManager(engine);
        assertThat(gc.gc().collectedVersions()).isZero();
        gc.close();
    }

    @Test
    void preservesVersionsAboveSafePoint() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 5; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(30));
        gc.gc();
        assertThat(engine.versions(bytes("k"))).hasSize(3);
        gc.close();
    }

    @Test
    void activeSnapshotProtectedBySafePoint() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 5; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        // 活跃事务 startTS=35：safePoint=25（低于活跃快照）→ v3 保留
        gc.updateSafePoint(new SafePoint(25));
        gc.gc();
        assertThat(engine.read(bytes("k"), 35).value()).isEqualTo(bytes("v3"));
        gc.close();
    }

    @Test
    void gcBytesReported() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 4; i++) {
            engine.putVersion(bytes("k"), bytes("value-" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(100));
        MvccGcManager.GcResult result = gc.gc();
        assertThat(result.collectedBytes()).isGreaterThan(0);
        gc.close();
    }

    @Test
    void gcIdempotent() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 3; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(gc.gc().collectedVersions()).isZero();
        gc.close();
    }

    @Test
    void scheduledGcRuns() throws Exception {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 4; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(100));
        gc.startScheduled(20);
        Thread.sleep(200);
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        gc.close();
    }

    @Test
    void gcPreservesDeleteMask() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(engine.latestValue(bytes("k"))).isNull();
        gc.close();
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {3, 5, 10, 20})
    void parameterizedGcRetainsLatest(int versionCount) {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= versionCount; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        gc.gc();
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v" + versionCount));
        gc.close();
    }

    @ParameterizedTest(name = "safePoint {0}")
    @ValueSource(longs = {15, 25, 35, 45})
    void parameterizedSafePointRetention(long safePoint) {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 5; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10,
                    WriteType.PUT);
        }
        MvccGcManager gc = new MvccGcManager(engine);
        gc.updateSafePoint(new SafePoint(safePoint));
        gc.gc();
        int retained = engine.versions(bytes("k")).size();
        assertThat(retained).isEqualTo(5 - (safePoint - 1) / 10);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v5"));
        gc.close();
    }

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
