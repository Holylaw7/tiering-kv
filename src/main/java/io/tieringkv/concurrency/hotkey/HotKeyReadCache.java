package io.tieringkv.concurrency.hotkey;

import io.tieringkv.concurrency.RequestCoalescer;
import io.tieringkv.storage.StorageEngine;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热点本地读缓存（ADR-0025）：短 TTL + 写失效；未命中走请求合并单 loader。
 * 热点子集实现"无锁读"（全量读仍走分段读锁，ADR-0024）。
 */
public final class HotKeyReadCache {

    private final HotKeyDetector detector;
    private final HotKeyPolicy policy;
    private final StorageEngine storage;
    private final RequestCoalescer coalescer = new RequestCoalescer();
    private final ConcurrentHashMap<ByteBuffer, CachedValue> cache = new ConcurrentHashMap<>();

    public HotKeyReadCache(HotKeyDetector detector, HotKeyPolicy policy, StorageEngine storage) {
        this.detector = detector;
        this.policy = policy;
        this.storage = storage;
    }

    public byte[] get(byte[] key, long nowMillis) {
        detector.recordAndCheck(key, nowMillis);
        if (!detector.isHot(key)) {
            return storage.get(key);
        }
        ByteBuffer wrapped = ByteBuffer.wrap(key);
        CachedValue cached = cache.get(wrapped);
        long currentVersion = storage.versionOf(key);
        if (cached != null) {
            // 版本一致即新鲜（消除 TTL 陈旧窗口，ADR-0328）；
            // 无版本存储（version=0）回退 TTL 兜底。
            if (cached.version() == currentVersion
                    && (cached.version() != 0
                    || cached.expireAt() > nowMillis)) {
                return cached.value();
            }
        }
        byte[] value = coalescer.coalesce(wrapped, () -> storage.get(key));
        if (value != null) {
            cache.put(wrapped, new CachedValue(value,
                    nowMillis + policy.cacheTtlMillis(),
                    storage.versionOf(key)));
        }
        return value;
    }

    public void invalidate(byte[] key) {
        ByteBuffer wrapped = ByteBuffer.wrap(key);
        cache.remove(wrapped);
        detector.invalidate(key);
    }

    private record CachedValue(byte[] value, long expireAt,
                               long version) {
    }
}
