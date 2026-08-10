package io.tieringkv.storage.io;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IORecoveryTest {

    @TempDir
    Path dir;

    private static ColdStorageEngine.Config config(Path dir) {
        return new ColdStorageEngine.Config(dir, 4096, 10, 1, 100);
    }

    @Test
    void ioPathSurvivesRestart() throws Exception {
        ColdStorageEngine.Config config = config(dir);
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(CachePolicy.defaults(), pool);
        IOStatistics stats = new IOStatistics();
        try (ColdStorageEngine cold = new ColdStorageEngine(config, cache, stats, true)) {
            for (int i = 0; i < 50; i++) {
                cold.put(KeyValueEntry.live(
                        ("k" + i).getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, i));
            }
            assertThat(cold.get("k25".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
        MemoryPool pool2 = new MemoryPool();
        BlockCache cache2 = new BlockCache(CachePolicy.defaults(), pool2);
        IOStatistics stats2 = new IOStatistics();
        try (ColdStorageEngine cold = new ColdStorageEngine(config, cache2, stats2, true)) {
            assertThat(cold.get("k25".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(stats2.snapshot().cacheMiss()).isPositive();
            assertThat(cold.get("k25".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(stats2.snapshot().cacheHit()).isPositive();
            assertThat(stats2.snapshot().mappedBytes()).isPositive();
        }
    }

    @Test
    void compactionInvalidatesCache() throws Exception {
        ColdStorageEngine.Config config = config(dir);
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(1024), pool);
        try (ColdStorageEngine cold = new ColdStorageEngine(
                config, cache, new IOStatistics(), true)) {
            cold.writeTable(entries("a", "b", "c"));
            cold.writeTable(entries("d", "e", "f"));
            assertThat(cold.get("a".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(cache.size()).isPositive();
            cold.compactAll();
            assertThat(cache.size()).isZero(); // 输入表已失效
            assertThat(cold.get("a".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(cold.get("f".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    private static List<KeyValueEntry> entries(String... keys) {
        java.util.List<KeyValueEntry> result = new java.util.ArrayList<>();
        long version = 1;
        for (String key : keys) {
            result.add(KeyValueEntry.live(
                    key.getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, version++));
        }
        return result;
    }
}
