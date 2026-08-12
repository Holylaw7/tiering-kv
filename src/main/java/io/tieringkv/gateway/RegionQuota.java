package io.tieringkv.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 地域写入配额（ADR-0149）：周期配额 + 用量计数。 */
public final class RegionQuota {

    private final Map<String, AtomicLong> quotas =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> used =
            new ConcurrentHashMap<>();

    public void setQuota(String region, long quota) {
        if (quota < 0) {
            throw new IllegalArgumentException(
                    "quota must be non-negative");
        }
        quotas.put(region, new AtomicLong(quota));
    }

    /** 原子获取：配额内返回 true，否则 false。 */
    public boolean tryAcquire(String region) {
        AtomicLong limit = quotas.get(region);
        if (limit == null) {
            throw new IllegalArgumentException(
                    "unknown region " + region);
        }
        AtomicLong counter = used.computeIfAbsent(region,
                ignored -> new AtomicLong());
        while (true) {
            long current = counter.get();
            if (current >= limit.get()) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public long remaining(String region) {
        AtomicLong limit = quotas.get(region);
        if (limit == null) {
            throw new IllegalArgumentException(
                    "unknown region " + region);
        }
        return Math.max(0, limit.get() - used(region));
    }

    public long used(String region) {
        AtomicLong counter = used.get(region);
        return counter == null ? 0 : counter.get();
    }

    public long quota(String region) {
        AtomicLong limit = quotas.get(region);
        return limit == null ? 0 : limit.get();
    }

    /** 周期重置：清空用量。 */
    public void resetCycle() {
        used.clear();
    }
}
