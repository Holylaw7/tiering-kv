package io.tieringkv.gateway;

import java.util.EnumMap;
import java.util.Map;

/** 全球流量策略（ADR-0149）：优先级 → QPS/配额映射。 */
public final class TrafficPolicy {

    /** 单优先级策略：QPS 上限 + 配额占比（0~1）。 */
    public record PolicyEntry(long qps, double quotaFraction) {

        public PolicyEntry {
            if (qps < 0) {
                throw new IllegalArgumentException(
                        "qps must be non-negative");
            }
            if (quotaFraction < 0 || quotaFraction > 1) {
                throw new IllegalArgumentException(
                        "quota fraction must be in [0,1]");
            }
        }
    }

    private final Map<PriorityRouter.Priority, PolicyEntry> policies =
            new EnumMap<>(PriorityRouter.Priority.class);
    private final Map<PriorityRouter.Priority, java.util.concurrent.atomic.AtomicLong> current =
            new EnumMap<>(PriorityRouter.Priority.class);

    public TrafficPolicy(Map<PriorityRouter.Priority, PolicyEntry>
                                 policies) {
        this.policies.putAll(policies);
        for (PriorityRouter.Priority priority
                : PriorityRouter.Priority.values()) {
            this.current.put(priority,
                    new java.util.concurrent.atomic.AtomicLong());
        }
    }

    public long qps(PriorityRouter.Priority priority) {
        PolicyEntry entry = policies.get(priority);
        return entry == null ? 0 : entry.qps();
    }

    /** 配额占比 × 总配额 → 该优先级配额。 */
    public long quotaFor(long totalQuota,
                         PriorityRouter.Priority priority) {
        PolicyEntry entry = policies.get(priority);
        if (entry == null) {
            return 0;
        }
        return Math.round(totalQuota * entry.quotaFraction());
    }

    /** 当前 QPS 是否允许（原子计数）。 */
    public boolean allows(PriorityRouter.Priority priority) {
        PolicyEntry entry = policies.get(priority);
        if (entry == null) {
            return false;
        }
        java.util.concurrent.atomic.AtomicLong counter =
                current.get(priority);
        while (true) {
            long value = counter.get();
            if (value >= entry.qps()) {
                return false;
            }
            if (counter.compareAndSet(value, value + 1)) {
                return true;
            }
        }
    }

    public long current(PriorityRouter.Priority priority) {
        java.util.concurrent.atomic.AtomicLong counter =
                current.get(priority);
        return counter == null ? 0 : counter.get();
    }

    public void reset() {
        current.values().forEach(
                value -> value.set(0));
    }
}
