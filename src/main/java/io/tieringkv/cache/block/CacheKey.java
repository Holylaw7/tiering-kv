package io.tieringkv.cache.block;

/** Block Cache 键（ADR-0028）：表 id + 块偏移。 */
public record CacheKey(long tableId, long blockOffset) {
}
