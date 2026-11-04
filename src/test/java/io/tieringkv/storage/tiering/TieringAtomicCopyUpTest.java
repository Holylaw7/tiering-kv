package io.tieringkv.storage.tiering;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-086 S3：冷层键的 read-modify-write 必须先回填热层，否则会丢前缀数据。 */
class TieringAtomicCopyUpTest {

    @TempDir
    Path dir;

    private StorageEngine hot;
    private ColdStorageEngine cold;
    private TieringReadStorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        hot = MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 20));
        cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(dir.resolve("cold")));
        engine = new TieringReadStorageEngine(hot, cold);
    }

    @AfterEach
    void tearDown() throws IOException {
        cold.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static KeyValueEntry entry(String key, String value, long expireTimestamp) {
        byte[] k = bytes(key);
        byte[] v = bytes(value);
        long now = System.currentTimeMillis();
        return new KeyValueEntry(k, v, now, now, expireTimestamp, 1L, false,
                KeyValueEntry.sizeOf(k, v));
    }

    @Test
    void incrementWorksOnColdKey() {
        cold.put(entry("n", "41", -1));

        assertThat(engine.increment(bytes("n"), 1)).isEqualTo(42);
        assertThat(text(engine.get(bytes("n")))).isEqualTo("42");
        assertThat(cold.liveCount()).isZero();
    }

    @Test
    void appendKeepsColdPrefix() {
        cold.put(entry("s", "ab", -1));

        assertThat(engine.append(bytes("s"), bytes("cd"))).isEqualTo(4);
        assertThat(text(engine.get(bytes("s")))).isEqualTo("abcd");
    }

    @Test
    void getSetReturnsColdValue() {
        cold.put(entry("k", "old", -1));

        assertThat(text(engine.getSet(bytes("k"), bytes("new")))).isEqualTo("old");
        assertThat(text(engine.get(bytes("k")))).isEqualTo("new");
        assertThat(cold.liveCount()).isZero();
    }

    @Test
    void getDeleteReturnsColdValueAndRemovesIt() {
        cold.put(entry("k", "v", -1));

        assertThat(text(engine.getDelete(bytes("k")))).isEqualTo("v");
        assertThat(engine.get(bytes("k"))).isNull();
    }

    @Test
    void setNxRefusesExistingColdKey() {
        cold.put(entry("k", "original", -1));

        assertThat(engine.putIfAbsent(bytes("k"), bytes("other"))).isFalse();
        assertThat(text(engine.get(bytes("k")))).isEqualTo("original");
    }

    @Test
    void ttlReflectsColdEntry() {
        long expireAt = System.currentTimeMillis() + 30_000;
        cold.put(entry("with-ttl", "v", expireAt));
        cold.put(entry("no-ttl", "v", -1));

        assertThat(engine.ttlMillis(bytes("with-ttl"))).isBetween(1L, 30_000L);
        assertThat(engine.ttlMillis(bytes("no-ttl"))).isEqualTo(-1);
        assertThat(engine.ttlMillis(bytes("absent"))).isEqualTo(-2);
    }

    @Test
    void expireOnColdKeyIsApplied() {
        cold.put(entry("k", "v", -1));

        assertThat(engine.expireAt(bytes("k"), System.currentTimeMillis() + 5_000)).isTrue();
        assertThat(engine.ttlMillis(bytes("k"))).isBetween(1L, 5_000L);
        assertThat(text(engine.get(bytes("k")))).isEqualTo("v");
    }

    @Test
    void persistRemovesColdTtl() {
        cold.put(entry("k", "v", System.currentTimeMillis() + 30_000));

        assertThat(engine.persist(bytes("k"))).isTrue();
        assertThat(engine.ttlMillis(bytes("k"))).isEqualTo(-1);
        assertThat(text(engine.get(bytes("k")))).isEqualTo("v");
    }
}
