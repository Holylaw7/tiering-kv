package io.tieringkv.saas;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** SaaS 计量（ADR-0124）：请求/存储/流量累计。 */
public final class UsageMeter {

    public enum MeterType {
        REQUESTS,
        STORAGE_GB,
        EGRESS_GB
    }

    private final Map<MeterType, AtomicLong> usage =
            new ConcurrentHashMap<>();

    public void record(MeterType type, long amount) {
        usage.computeIfAbsent(type,
                ignored -> new AtomicLong()).addAndGet(amount);
    }

    public long get(MeterType type) {
        return usage.getOrDefault(type,
                new AtomicLong()).get();
    }

    public Map<MeterType, Long> snapshot() {
        Map<MeterType, Long> result = new ConcurrentHashMap<>();
        usage.forEach((type, value) -> result.put(type, value.get()));
        return Map.copyOf(result);
    }

    public void reset() {
        usage.clear();
    }
}
