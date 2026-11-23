package io.tieringkv.storage.io;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** IO 统计（ADR-0026）。pageFault 在 JVM 不可直接观测（以冷读延迟代理）。 */
public final class IOStatistics {

    private final LongAdder readCount = new LongAdder();
    private final LongAdder readLatencyNanos = new LongAdder();
    private final LongAdder cacheHit = new LongAdder();
    private final LongAdder cacheMiss = new LongAdder();
    private final AtomicLong mappedBytes = new AtomicLong();

    public void recordRead(long latencyNanos) {
        readCount.increment();
        readLatencyNanos.add(latencyNanos);
    }

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    public void setMappedBytes(long bytes) {
        mappedBytes.set(bytes);
    }

    public Snapshot snapshot() {
        long reads = readCount.sum();
        return new Snapshot(
                reads,
                reads == 0 ? 0 : readLatencyNanos.sum() / reads,
                cacheHit.sum(),
                cacheMiss.sum(),
                mappedBytes.get());
    }

    public record Snapshot(
            long readCount,
            long avgReadLatencyNanos,
            long cacheHit,
            long cacheMiss,
            long mappedBytes) {
    }
}
