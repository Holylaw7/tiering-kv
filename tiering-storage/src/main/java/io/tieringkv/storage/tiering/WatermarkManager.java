package io.tieringkv.storage.tiering;

/**
 * 水位管理（ADR-0021）：used/max 比例 + entryCount + 迁移队列阈值
 * 综合判定 NORMAL / WARNING / CRITICAL。
 */
public final class WatermarkManager {

    public record Config(
            double lowFraction,
            double highFraction,
            double criticalFraction,
            long maxEntryCount,
            long warningQueueTasks,
            long criticalQueueTasks) {

        public static Config defaults() {
            return new Config(0.70, 0.85, 0.95, 1_000_000, 5_000, 10_000);
        }
    }

    private final Config config;

    public WatermarkManager(Config config) {
        this.config = config;
    }

    public TierState evaluate(long usedBytes, long maxBytes, long entryCount, long pendingTasks) {
        double ratio = usedBytes / (double) Math.max(1, maxBytes);
        if (ratio >= config.criticalFraction() || pendingTasks >= config.criticalQueueTasks()) {
            return TierState.CRITICAL;
        }
        if (ratio >= config.highFraction()
                || entryCount >= config.maxEntryCount()
                || pendingTasks >= config.warningQueueTasks()) {
            return TierState.WARNING;
        }
        return TierState.NORMAL;
    }

    /** 是否需要触发异步 Flush（HIGH 水位或 entryCount 阈值）。 */
    public boolean isFlushNeeded(long usedBytes, long maxBytes, long entryCount) {
        double ratio = usedBytes / (double) Math.max(1, maxBytes);
        return ratio >= config.highFraction() || entryCount >= config.maxEntryCount();
    }

    public Config config() {
        return config;
    }
}
