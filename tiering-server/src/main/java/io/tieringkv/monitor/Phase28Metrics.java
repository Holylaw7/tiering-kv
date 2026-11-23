package io.tieringkv.monitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 28 可观测性（Goal 8）：复制/容灾/查询/向量指标。 */
public final class Phase28Metrics {

    private final Map<String, AtomicLong> counters =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges =
            new ConcurrentHashMap<>();

    public void increment(String name) {
        counters.computeIfAbsent(name,
                ignored -> new AtomicLong()).incrementAndGet();
    }

    public void gauge(String name, long value) {
        gauges.put(name, new AtomicLong(value));
    }

    public long counter(String name) {
        return counters.getOrDefault(name,
                new AtomicLong()).get();
    }

    public long gauge(String name) {
        return gauges.getOrDefault(name,
                new AtomicLong()).get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        counters.forEach((name, value) ->
                result.put(name, value.get()));
        gauges.forEach((name, value) ->
                result.put(name, value.get()));
        return Map.copyOf(result);
    }
}
