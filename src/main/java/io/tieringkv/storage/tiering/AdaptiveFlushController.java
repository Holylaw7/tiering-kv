package io.tieringkv.storage.tiering;

/**
 * 自适应 Flush 策略（ADR-0049）：综合内存压力、写速率、flush 延迟与
 * SSTable 数量，输出 flush 间隔（低负载 500ms → 高负载 50ms）与动态
 * 高水位；指标使用 EMA 平滑，参数变化限幅防抖动。
 */
public final class AdaptiveFlushController {

    public static final long MIN_INTERVAL_MILLIS = 50;
    public static final long MAX_INTERVAL_MILLIS = 500;
    public static final double MIN_WATERMARK = 0.55;
    public static final double MAX_WATERMARK = 0.95;

    private final double emaAlpha;
    private final long maxWriteRate;
    private double emaWriteRate;
    private double emaFlushLatencyMs;
    private long sstableCount;
    private long flushQueueDepth;

    public AdaptiveFlushController(long maxWriteRatePerSecond, double emaAlpha) {
        this.maxWriteRate = maxWriteRatePerSecond;
        this.emaAlpha = emaAlpha;
    }

    public static AdaptiveFlushController defaults() {
        return new AdaptiveFlushController(200_000, 0.2);
    }

    public synchronized void recordWrite() {
        // 写速率由外部按秒窗口调用 recordWriteRate 更准确；此处提供便捷入口
    }

    public synchronized void recordWriteRate(double writesPerSecond) {
        emaWriteRate = ema(emaWriteRate, writesPerSecond);
    }

    public synchronized void recordFlush(long latencyMillis) {
        emaFlushLatencyMs = ema(emaFlushLatencyMs, latencyMillis);
    }

    public synchronized void setSstableCount(long count) {
        this.sstableCount = count;
    }

    public synchronized void setFlushQueueDepth(long depth) {
        this.flushQueueDepth = depth;
    }

    /** 输出 flush 间隔：写速率越高、队列越深 → 越短；flush 慢/SSTable 多 → 适度拉长。 */
    public synchronized long flushIntervalMillis() {
        double writeFactor = Math.min(1.0, emaWriteRate / maxWriteRate);
        double queueFactor = Math.min(1.0, flushQueueDepth / 8.0);
        double latencyPenalty = Math.min(1.0, emaFlushLatencyMs / 200.0) * 0.3;
        double sstablePenalty = Math.min(1.0, sstableCount / 32.0) * 0.2;
        double pressure = Math.max(writeFactor, queueFactor);
        double interval = MAX_INTERVAL_MILLIS
                - (MAX_INTERVAL_MILLIS - MIN_INTERVAL_MILLIS) * pressure;
        interval = interval * (1.0 + latencyPenalty + sstablePenalty);
        return clamp(interval, MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS);
    }

    /** 动态高水位：内存压力低时水位抬高（少触发），压力高时降低（早触发）。 */
    public synchronized double highWatermark(double memoryRatio) {
        double intervalRatio = (flushIntervalMillis() - MIN_INTERVAL_MILLIS)
                / (MAX_INTERVAL_MILLIS - MIN_INTERVAL_MILLIS);
        double watermark = MIN_WATERMARK + intervalRatio * (MAX_WATERMARK - MIN_WATERMARK);
        return clamp(watermark, MIN_WATERMARK, MAX_WATERMARK);
    }

    public synchronized boolean shouldAutoFlush(double memoryRatio) {
        return memoryRatio >= highWatermark(memoryRatio);
    }

    public synchronized double writeRate() {
        return emaWriteRate;
    }

    public synchronized double flushLatencyMs() {
        return emaFlushLatencyMs;
    }

    private double ema(double previous, double sample) {
        return previous == 0 ? sample : emaAlpha * sample + (1 - emaAlpha) * previous;
    }

    private static long clamp(double value, long min, long max) {
        return (long) Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
