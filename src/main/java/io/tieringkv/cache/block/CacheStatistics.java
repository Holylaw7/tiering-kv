package io.tieringkv.cache.block;

import java.util.concurrent.atomic.LongAdder;

/** Block Cache 统计（ADR-0028）。 */
public final class CacheStatistics {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    void hit() {
        hits.increment();
    }

    void miss() {
        misses.increment();
    }

    void eviction() {
        evictions.increment();
    }

    public Snapshot snapshot() {
        long hit = hits.sum();
        long miss = misses.sum();
        double rate = hit + miss == 0 ? 0 : hit / (double) (hit + miss);
        return new Snapshot(hit, miss, evictions.sum(), rate);
    }

    public record Snapshot(long hits, long misses, long evictions, double hitRate) {
    }
}
