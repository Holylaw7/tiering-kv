package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MVCC 存储（ADR-0071）：版本写入/删除/读取/扫描/编码。 */
class MvccStorageTest {

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    @Test
    void putVersionStoresVersion() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.versions(bytes("k")).get(0).commitTS()).isEqualTo(10);
    }

    @Test
    void multipleVersionsOrdered() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("c"), 3, 30, WriteType.PUT);
        List<MvccEntry> versions = engine.versions(bytes("k"));
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).commitTS()).isEqualTo(10);
        assertThat(versions.get(2).commitTS()).isEqualTo(30);
    }

    @Test
    void deleteVersionRemovesVersion() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        engine.deleteVersion(bytes("k"), 10);
        assertThat(engine.versions(bytes("k"))).isEmpty();
    }

    @Test
    void latestValueReturnsNewest() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("b"));
    }

    @Test
    void latestValueNullWhenDeleted() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        assertThat(engine.latestValue(bytes("k"))).isNull();
    }

    @Test
    void readAtTsSeesOnlyCommitted() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        assertThat(engine.read(bytes("k"), 15).value()).isEqualTo(bytes("a"));
        assertThat(engine.read(bytes("k"), 25).value()).isEqualTo(bytes("b"));
    }

    @Test
    void deleteHidesOlderVersions() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        MvccEntry entry = engine.read(bytes("k"), 30);
        assertThat(entry.isDelete()).isTrue();
    }

    @Test
    void lockVersionInvisibleToReader() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("provisional"), 1, 1, WriteType.LOCK);
        assertThat(engine.read(bytes("k"), Long.MAX_VALUE)).isNull();
    }

    @Test
    void scanReturnsVisibleRange() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("b"), bytes("2"), 2, 20, WriteType.PUT);
        engine.putVersion(bytes("c"), bytes("3"), 3, 30, WriteType.PUT);
        var result = engine.scan(bytes("a"), bytes("c"), Long.MAX_VALUE);
        assertThat(result).containsKey(bytes("a")).containsKey(bytes("b"));
        assertThat(result).doesNotContainKey(bytes("c"));
    }

    @Test
    void scanHidesDeleted() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("a"), null, 2, 20, WriteType.DELETE);
        var result = engine.scan(bytes("a"), bytes("z"), Long.MAX_VALUE);
        assertThat(result).doesNotContainKey(bytes("a"));
    }

    @Test
    void versionCountTracks() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        assertThat(engine.versionCount()).isEqualTo(2);
    }

    @Test
    void mvccKeyRoundTrip() {
        byte[] userKey = bytes("user:100");
        byte[] encoded = MvccKey.encode(userKey, 1, 105, WriteType.PUT);
        assertThat(MvccKey.userKey(encoded)).isEqualTo(userKey);
        assertThat(MvccKey.commitTS(encoded)).isEqualTo(105);
        assertThat(MvccKey.startTS(encoded)).isEqualTo(1);
        assertThat(MvccKey.writeType(encoded)).isEqualTo(WriteType.PUT);
        assertThat(MvccKey.startsWith(encoded, userKey)).isTrue();
        assertThat(MvccKey.startsWith(encoded, bytes("user"))).isFalse();
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {2, 3, 5, 10, 50})
    void parameterizedVersionCount(int count) {
        MvccStorageEngine engine = engine();
        for (int i = 1; i <= count; i++) {
            engine.putVersion(bytes("k"), bytes("v" + i), i, i * 10, WriteType.PUT);
        }
        assertThat(engine.versions(bytes("k"))).hasSize(count);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v" + count));
    }

    @ParameterizedTest(name = "readTs {0}")
    @ValueSource(longs = {5, 15, 25, 35, Long.MAX_VALUE})
    void parameterizedReadVisibility(long readTS) {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("b"), 2, 20, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("c"), 3, 30, WriteType.PUT);
        MvccEntry entry = engine.read(bytes("k"), readTS);
        if (readTS >= 30) {
            assertThat(entry.value()).isEqualTo(bytes("c"));
        } else if (readTS >= 20) {
            assertThat(entry.value()).isEqualTo(bytes("b"));
        } else if (readTS >= 10) {
            assertThat(entry.value()).isEqualTo(bytes("a"));
        } else {
            assertThat(entry).isNull();
        }
    }

    @ParameterizedTest(name = "scanEnd {0}")
    @ValueSource(strings = {"a", "b", "c", "d"})
    void parameterizedScanEnd(String end) {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a"), bytes("1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("b"), bytes("2"), 2, 20, WriteType.PUT);
        engine.putVersion(bytes("c"), bytes("3"), 3, 30, WriteType.PUT);
        var result = engine.scan(bytes("a"), bytes(end), Long.MAX_VALUE);
        assertThat(result.size()).isEqualTo(Math.max(0, end.charAt(0) - 'a'));
    }

    @Test
    void deleteThenRewrite() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        engine.putVersion(bytes("k"), bytes("new"), 3, 30, WriteType.PUT);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("new"));
    }

    @Test
    void underlyingStoragePreserved() {
        MemTable table = MemTable.create();
        MvccStorageEngine engine = new MvccStorageEngine(table);
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        assertThat(table.get(MvccKey.encode(bytes("k"), 1, 10, WriteType.PUT)))
                .isNotNull();
        table.close();
    }

    @Test
    void versionsAcrossKeysIsolated() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k1"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k2"), bytes("b"), 2, 20, WriteType.PUT);
        assertThat(engine.versions(bytes("k1"))).hasSize(1);
        assertThat(engine.versions(bytes("k2"))).hasSize(1);
    }

    @Test
    void readIgnoresNewerVersions() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("old"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("new"), 2, 100, WriteType.PUT);
        assertThat(engine.read(bytes("k"), 50).value()).isEqualTo(bytes("old"));
    }

    @Test
    void tombstoneThenOlderRead() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("a"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        assertThat(engine.read(bytes("k"), 15).value()).isEqualTo(bytes("a"));
        assertThat(engine.read(bytes("k"), 25).isDelete()).isTrue();
    }

    @Test
    void emptyStorageReads() {
        MvccStorageEngine engine = engine();
        assertThat(engine.read(bytes("k"), 100)).isNull();
        assertThat(engine.latestValue(bytes("k"))).isNull();
        assertThat(engine.scan(bytes("a"), bytes("z"), 100)).isEmpty();
    }

    @Test
    void versionStartTsTracked() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 42, 100, WriteType.PUT);
        assertThat(engine.versions(bytes("k")).get(0).startTS()).isEqualTo(42);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
