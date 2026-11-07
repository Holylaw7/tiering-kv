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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-086：迁移后的键必须能通过主读路径读回。 */
class TieringReadStorageEngineTest {

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

    private static KeyValueEntry entry(String key, String value, long expireTimestamp) {
        byte[] k = bytes(key);
        byte[] v = bytes(value);
        long now = System.currentTimeMillis();
        return new KeyValueEntry(k, v, now, now, expireTimestamp, 1L, false,
                KeyValueEntry.sizeOf(k, v));
    }

    @Test
    void readsColdTierWhenHotMisses() {
        cold.put(entry("cold:1", "value-1", -1));

        assertThat(engine.get(bytes("cold:1"))).isEqualTo(bytes("value-1"));
        assertThat(hot.get(bytes("cold:1"))).isNull();
    }

    @Test
    void hotTierWinsOverColdTier() {
        cold.put(entry("k", "old", -1));
        hot.put(bytes("k"), bytes("new"));

        assertThat(engine.get(bytes("k"))).isEqualTo(bytes("new"));
    }

    @Test
    void existsCoversBothTiers() {
        cold.put(entry("cold:2", "v", -1));
        hot.put(bytes("hot:2"), bytes("v"));

        assertThat(engine.exists(bytes("cold:2"))).isTrue();
        assertThat(engine.exists(bytes("hot:2"))).isTrue();
        assertThat(engine.exists(bytes("absent"))).isFalse();
    }

    @Test
    void deleteRemovesColdTierAndPreventsResurrection() {
        cold.put(entry("cold:3", "v", -1));

        assertThat(engine.delete(bytes("cold:3"))).isTrue();
        assertThat(engine.get(bytes("cold:3"))).isNull();
        assertThat(engine.exists(bytes("cold:3"))).isFalse();
        assertThat(cold.liveCount()).isZero();
    }

    @Test
    void sizeSumsBothTiers() {
        hot.put(bytes("h1"), bytes("1"));
        hot.put(bytes("h2"), bytes("2"));
        cold.put(entry("c1", "1", -1));
        cold.put(entry("c2", "2", -1));
        cold.put(entry("c3", "3", -1));

        // ADR-0370：冷层计数为后台快照，写入后显式刷新
        cold.refreshLiveCount();
        assertThat(engine.size()).isEqualTo(5);
    }

    @Test
    void iteratorMergesSortedAndDeduplicates() {
        hot.put(bytes("a"), bytes("hot-a"));
        hot.put(bytes("c"), bytes("hot-c"));
        cold.put(entry("b", "cold-b", -1));
        cold.put(entry("c", "cold-c-old", -1));
        cold.put(entry("d", "cold-d", -1));

        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (var iterator = engine.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry e = iterator.next();
                keys.add(new String(e.key(), StandardCharsets.UTF_8));
                values.add(new String(e.value(), StandardCharsets.UTF_8));
            }
        }

        assertThat(keys).containsExactly("a", "b", "c", "d");
        assertThat(values).containsExactly("hot-a", "cold-b", "hot-c", "cold-d");
    }

    @Test
    void ttlIsPreservedForColdEntries() {
        long expireAt = System.currentTimeMillis() + 30_000;
        cold.put(entry("cold:ttl", "v", expireAt));

        KeyValueEntry found = cold.entry(bytes("cold:ttl"));

        assertThat(found).isNotNull();
        assertThat(found.expireTimestamp()).isEqualTo(expireAt);
        assertThat(engine.get(bytes("cold:ttl"))).isEqualTo(bytes("v"));
    }
}