package io.tieringkv.cluster.routing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由缓存（ADR-0066）：key→entry，携带表版本；陈旧自动回源刷新。
 */
public final class RoutingCache {

    private final UnifiedRouter router;
    private final Map<ByteKey, CachedEntry> cache = new ConcurrentHashMap<>();

    public RoutingCache(UnifiedRouter router) {
        this.router = router;
    }

    /** 命中且版本一致直接返回；否则回源刷新。 */
    public RoutingTableEntry route(byte[] key) {
        CachedEntry cached = cache.get(new ByteKey(key));
        long currentVersion = router.version();
        if (cached != null && cached.version() == currentVersion) {
            return cached.entry();
        }
        RoutingTableEntry entry = router.route(key);
        cache.put(new ByteKey(key), new CachedEntry(entry, currentVersion));
        return entry;
    }

    public void invalidate(byte[] key) {
        cache.remove(new ByteKey(key));
    }

    public void invalidateAll() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    private record CachedEntry(RoutingTableEntry entry, long version) {
    }
}
