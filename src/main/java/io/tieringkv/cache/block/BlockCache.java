package io.tieringkv.cache.block;

import io.tieringkv.memory.MemoryPool;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSTable Block Cache（ADR-0028）：LRU（accessOrder）；缓存体为池化
 * DirectByteBuffer，淘汰时回池。
 */
public final class BlockCache {

    private final CachePolicy policy;
    private final MemoryPool memoryPool;
    private final CacheStatistics statistics = new CacheStatistics();
    private final LinkedHashMap<CacheKey, CacheEntry> map =
            new LinkedHashMap<>(64, 0.75f, true);

    public BlockCache(CachePolicy policy, MemoryPool memoryPool) {
        this.policy = policy;
        this.memoryPool = memoryPool;
    }

    /** 命中返回缓冲副本（position 0）；未命中返回 null。 */
    public synchronized ByteBuffer get(CacheKey key) {
        CacheEntry entry = map.get(key);
        if (entry == null) {
            statistics.miss();
            return null;
        }
        statistics.hit();
        return entry.buffer().duplicate();
    }

    public synchronized void put(CacheKey key, ByteBuffer data) {
        if (policy.capacity() <= 0 || map.containsKey(key)) {
            return;
        }
        ByteBuffer copy = memoryPool.allocateRaw(data.remaining());
        copy.put(data.duplicate());
        copy.flip();
        map.put(key, new CacheEntry(copy));
        while (map.size() > policy.capacity()) {
            Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = map.entrySet().iterator();
            CacheEntry evicted = iterator.next().getValue();
            iterator.remove();
            memoryPool.release(evicted.buffer());
            statistics.eviction();
        }
    }

    /** 失效某表的所有块（compaction 删除表时调用）。 */
    public synchronized void invalidate(long tableId) {
        map.entrySet().removeIf(entry -> {
            if (entry.getKey().tableId() == tableId) {
                memoryPool.release(entry.getValue().buffer());
                return true;
            }
            return false;
        });
    }

    public synchronized void clear() {
        for (CacheEntry entry : map.values()) {
            memoryPool.release(entry.buffer());
        }
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public CacheStatistics statistics() {
        return statistics;
    }
}
