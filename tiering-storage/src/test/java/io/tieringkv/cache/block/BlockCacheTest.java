package io.tieringkv.cache.block;

import io.tieringkv.memory.MemoryPool;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BlockCacheTest {

    @Test
    void getPutHitMiss() {
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(10), pool);
        CacheKey key = new CacheKey(1, 100);
        assertThat(cache.get(key)).isNull();
        cache.put(key, ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)));
        ByteBuffer hit = cache.get(key);
        assertThat(hit).isNotNull();
        byte[] bytes = new byte[hit.remaining()];
        hit.get(bytes);
        assertThat(bytes).isEqualTo("data".getBytes(StandardCharsets.UTF_8));
        assertThat(cache.statistics().snapshot().hitRate()).isEqualTo(0.5);
    }

    @Test
    void lruEvictsOldest() {
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(2), pool);
        cache.put(new CacheKey(1, 1), ByteBuffer.wrap(new byte[]{1}));
        cache.put(new CacheKey(1, 2), ByteBuffer.wrap(new byte[]{2}));
        cache.get(new CacheKey(1, 1)); // 刷新 1 的 recency
        cache.put(new CacheKey(1, 3), ByteBuffer.wrap(new byte[]{3}));
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get(new CacheKey(1, 2))).isNull(); // 2 被淘汰
        assertThat(cache.get(new CacheKey(1, 3))).isNotNull();
        assertThat(cache.statistics().snapshot().evictions()).isEqualTo(1);
    }

    @Test
    void invalidateByTableAndClear() {
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(10), pool);
        cache.put(new CacheKey(1, 1), ByteBuffer.wrap(new byte[]{1}));
        cache.put(new CacheKey(2, 1), ByteBuffer.wrap(new byte[]{2}));
        cache.invalidate(1);
        assertThat(cache.get(new CacheKey(1, 1))).isNull();
        assertThat(cache.get(new CacheKey(2, 1))).isNotNull();
        cache.clear();
        assertThat(cache.size()).isZero();
    }
}
