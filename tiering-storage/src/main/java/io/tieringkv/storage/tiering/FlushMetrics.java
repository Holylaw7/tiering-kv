package io.tieringkv.storage.tiering;

/** 自适应 Flush 指标（ADR-0049）：队列深度 / 延迟 / 写速率 / SSTable 数。 */
public record FlushMetrics(
        long flushQueueDepth,
        double flushLatencyMs,
        double writeRatePerSecond,
        long sstableCount) {

    public static FlushMetrics idle() {
        return new FlushMetrics(0, 0, 0, 0);
    }
}
