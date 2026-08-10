package io.tieringkv.cache.block;

/** Block Cache 策略（ADR-0028）：LRU，容量条目数（0 = 禁用）。 */
public record CachePolicy(int capacity) {

    public static CachePolicy defaults() {
        return new CachePolicy(1024);
    }
}
