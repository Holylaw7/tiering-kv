package io.tieringkv.cluster.routing;

import io.tieringkv.cluster.region.RegionEpoch;

/**
 * 路由纪元守卫（ADR-0066）：请求携带 epoch；与当前不符 → 刷新缓存并
 * 标记陈旧（网关据此输出 MOVED/ASK/TRYAGAIN）。
 */
public final class RouteEpochGuard {

    private final UnifiedRouter router;
    private final RoutingCache cache;

    public RouteEpochGuard(UnifiedRouter router, RoutingCache cache) {
        this.router = router;
        this.cache = cache;
    }

    /** 返回当前条目；陈旧返回 false（调用方应刷新客户端路由）。 */
    public GuardedRoute check(byte[] key, RegionEpoch expectedEpoch) {
        RoutingTableEntry current = cache.route(key);
        boolean fresh = expectedEpoch != null
                && !expectedEpoch.olderThan(current.epoch())
                && router.version() >= 0;
        return new GuardedRoute(current, fresh);
    }

    public boolean isStale(RoutingTableEntry entry) {
        RoutingTableEntry current = router.routeSlot(entry.slotStart());
        return entry.epoch().olderThan(current.epoch());
    }

    public UnifiedRouter router() {
        return router;
    }

    public record GuardedRoute(RoutingTableEntry entry, boolean fresh) {
    }
}
