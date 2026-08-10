package io.tieringkv.storage.cache;

/** 缓存策略配置（ADR-0011/0012）。 */
public record CacheConfig(
        long decayIntervalMillis,
        int arcCapacity,
        int maxEvictionsPerCycle) {

    public CacheConfig {
        if (decayIntervalMillis <= 0) {
            throw new IllegalArgumentException("decayIntervalMillis must be positive");
        }
        if (arcCapacity <= 0) {
            throw new IllegalArgumentException("arcCapacity must be positive");
        }
        if (maxEvictionsPerCycle <= 0) {
            throw new IllegalArgumentException("maxEvictionsPerCycle must be positive");
        }
    }

    public static CacheConfig defaults() {
        return new CacheConfig(60_000, 10_000, 1024);
    }
}
