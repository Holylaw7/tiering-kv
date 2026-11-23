package io.tieringkv.gateway;

import java.util.List;

/**
 * 全球多活优先级路由（ADR-0149）：配额不足时按优先级降级；
 * LOW 丢弃，HIGH/NORMAL 尝试备用地域。
 */
public final class PriorityRouter {

    public enum Priority {
        LOW,
        NORMAL,
        HIGH
    }

    /** 路由决策：目标地域 + 是否接受 + 是否降级。 */
    public record Decision(String region, boolean accepted,
                           boolean degraded) {
    }

    private final RegionAffinityRouter affinity;
    private final RegionQuota quota;
    private final List<String> regions;
    private final boolean degradeEnabled;

    public PriorityRouter(RegionAffinityRouter affinity,
                          RegionQuota quota, List<String> regions,
                          boolean degradeEnabled) {
        this.affinity = affinity;
        this.quota = quota;
        this.regions = List.copyOf(regions);
        this.degradeEnabled = degradeEnabled;
    }

    public Decision route(byte[] key, Priority priority) {
        String preferred = affinity.route(key);
        if (quota.tryAcquire(preferred)) {
            return new Decision(preferred, true, false);
        }
        if (priority == Priority.LOW || !degradeEnabled) {
            return new Decision(preferred, false, false);
        }
        for (String region : regions) {
            if (!region.equals(preferred) && quota.tryAcquire(region)) {
                return new Decision(region, true, true);
            }
        }
        return new Decision(preferred, false, false);
    }
}
