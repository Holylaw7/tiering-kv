package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Snapshot 读（ADR-0071）：最新/历史/删除/覆盖/隔离。 */
class MvccSnapshotTest {

    private final SnapshotReader reader = new SnapshotReader();

    @Test
    void latestRead() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        assertThat(reader.get(engine, bytes("k"), Long.MAX_VALUE))
                .isEqualTo(bytes("v2"));
    }

    @Test
    void historicalRead() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        assertThat(reader.get(engine, bytes("k"), 15)).isEqualTo(bytes("v1"));
    }

    @Test
    void deleteHidesOld() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        assertThat(reader.get(engine, bytes("k"), 30)).isNull();
    }

    @Test
    void deleteBeforeReadTsHides() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        assertThat(reader.get(engine, bytes("k"), 25)).isNull();
    }

    @Test
    void overwriteVisibleAtNewSnapshot() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        assertThat(reader.get(engine, bytes("k"), 10)).isEqualTo(bytes("a"));
        assertThat(reader.get(engine, bytes("k"), 20)).isEqualTo(bytes("b"));
    }

    @Test
    void snapshotIsolationBetweenReads() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        byte[] at10 = reader.get(engine, bytes("k"), 10);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        byte[] at15 = reader.get(engine, bytes("k"), 15);
        byte[] at25 = reader.get(engine, bytes("k"), 25);
        assertThat(at10).isEqualTo(bytes("a"));
        assertThat(at15).isEqualTo(bytes("a"));
        assertThat(at25).isEqualTo(bytes("b"));
    }

    @Test
    void concurrentVersionsDeterministic() throws Exception {
        MvccStorageEngine engine = engine();
        Thread t1 = new Thread(() -> engine.putVersion(
                bytes("k"), bytes("x"), 1, 11, WriteType.PUT));
        Thread t2 = new Thread(() -> engine.putVersion(
                bytes("k"), bytes("y"), 2, 12, WriteType.PUT));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(engine.versions(bytes("k"))).hasSize(2);
        assertThat(reader.get(engine, bytes("k"), Long.MAX_VALUE))
                .isIn(bytes("x"), bytes("y"));
    }

    @Test
    void scanSnapshot() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("b"), bytes("2"), 2, 20, WriteType.PUT);
        engine.putVersion(bytes("b"), bytes("2b"), 3, 30, WriteType.PUT);
        assertThat(reader.scan(engine, bytes("a"), bytes("z"), 25))
                .containsEntry(bytes("a"), bytes("1"))
                .containsEntry(bytes("b"), bytes("2"));
        assertThat(reader.scan(engine, bytes("a"), bytes("z"), 35))
                .containsEntry(bytes("b"), bytes("2b"));
    }

    @Test
    void scanExcludesLockedProvisional() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("provisional"), 1, 1, WriteType.LOCK);
        assertThat(reader.scan(engine, bytes("a"), bytes("z"), Long.MAX_VALUE))
                .isEmpty();
    }

    @Test
    void readMissingKey() {
        assertThat(reader.get(engine(), bytes("missing"), 100)).isNull();
    }

    @ParameterizedTest(name = "readTs {0}")
    @ValueSource(longs = {5, 15, 25, Long.MAX_VALUE})
    void parameterizedSnapshotRead(long readTS) {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        byte[] value = reader.get(engine, bytes("k"), readTS);
        if (readTS >= 20) {
            assertThat(value).isEqualTo(bytes("b"));
        } else if (readTS >= 10) {
            assertThat(value).isEqualTo(bytes("a"));
        } else {
            assertThat(value).isNull();
        }
    }

    @ParameterizedTest(name = "deleteAt {0}")
    @ValueSource(longs = {10, 15, 20, 25})
    void parameterizedDeleteVisibility(long readTS) {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        byte[] value = reader.get(engine, bytes("k"), readTS);
        if (readTS >= 20) {
            assertThat(value).isNull();
        } else {
            assertThat(value).isEqualTo(bytes("a"));
        }
    }

    @Test
    void snapshotAfterMultipleOverwrites() {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= 5; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10, WriteType.PUT);
        }
        assertThat(reader.get(engine, bytes("k"), 25)).isEqualTo(bytes("v2"));
        assertThat(reader.get(engine, bytes("k"), 35)).isEqualTo(bytes("v3"));
        assertThat(reader.get(engine, bytes("k"), 50)).isEqualTo(bytes("v5"));
    }

    @Test
    void readIgnoringLockRecords() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("locked"), 9, 9, WriteType.LOCK);
        engine.putVersion(bytes("k"), bytes("committed"), 1, 10, WriteType.PUT);
        assertThat(reader.get(engine, bytes("k"), Long.MAX_VALUE))
                .isEqualTo(bytes("committed"));
    }

    @Test
    void scanBoundaryInclusiveStart() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("1"), 1, 10, WriteType.PUT);
        assertThat(reader.scan(engine, bytes("a"), bytes("b"), 100))
                .containsKey(bytes("a"));
    }

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
