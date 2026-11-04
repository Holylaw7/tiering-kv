package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-086：冷层暴露存活条目与存活计数，供上层实现冷读回退与 DBSIZE 汇总。 */
class ColdStorageEngineEntryTest {

    @TempDir
    Path dir;

    private ColdStorageEngine cold;

    @BeforeEach
    void setUp() throws IOException {
        cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(dir.resolve("cold")));
    }

    @AfterEach
    void tearDown() throws IOException {
        cold.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static KeyValueEntry live(String key, String value, long expireTimestamp) {
        byte[] k = bytes(key);
        byte[] v = bytes(value);
        long now = System.currentTimeMillis();
        return new KeyValueEntry(k, v, now, now, expireTimestamp, 1L, false,
                KeyValueEntry.sizeOf(k, v));
    }

    @Test
    void entryExposesTtlForCopyUp() {
        long expireAt = System.currentTimeMillis() + 60_000;
        cold.put(live("k1", "v1", expireAt));

        KeyValueEntry entry = cold.entry(bytes("k1"));

        assertThat(entry).isNotNull();
        assertThat(entry.value()).isEqualTo(bytes("v1"));
        assertThat(entry.expireTimestamp()).isEqualTo(expireAt);
        assertThat(cold.get(bytes("k1"))).isEqualTo(bytes("v1"));
    }

    @Test
    void entryIsNullForDeletedKey() {
        cold.put(live("k1", "v1", -1));
        cold.delete(bytes("k1"));

        assertThat(cold.entry(bytes("k1"))).isNull();
        assertThat(cold.get(bytes("k1"))).isNull();
        assertThat(cold.liveCount()).isZero();
    }

    @Test
    void liveCountIgnoresOverwritesAndTracksDeletes() {
        cold.put(live("k1", "v1", -1));
        assertThat(cold.liveCount()).isEqualTo(1);

        cold.put(live("k1", "v2", -1));
        assertThat(cold.liveCount()).isEqualTo(1);

        cold.put(live("k2", "v2", -1));
        assertThat(cold.liveCount()).isEqualTo(2);

        cold.delete(bytes("k1"));
        assertThat(cold.liveCount()).isEqualTo(1);
        assertThat(cold.entry(bytes("k2"))).isNotNull();
    }

    @Test
    void expiredEntryIsNeitherReadableNorCounted() {
        cold.put(live("k1", "v1", System.currentTimeMillis() - 1_000));

        assertThat(cold.entry(bytes("k1"))).isNull();
        assertThat(cold.liveCount()).isZero();
    }

    @Test
    void iteratorStillSeesLiveEntries() {
        cold.put(live("a", "1", -1));
        cold.put(live("b", "2", -1));
        cold.delete(bytes("a"));

        long count = 0;
        try (var iterator = cold.iterator()) {
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }
}
