package io.tieringkv.monitor;

/**
 * 延迟统计（TD-096）：最近 60 秒窗口 + 自启动累计。
 *
 * <p>窗口在读取时惰性轮转（两个直方图互换），因此不需要额外线程；
 * 窗口内没有样本时回退到累计值，避免空闲后仪表盘显示 0。
 */
public final class LatencyStats {

    /** 窗口时长：60 秒。 */
    static final long WINDOW_MILLIS = 60_000L;

    private final LatencyHistogram cumulative = new LatencyHistogram();
    private final LatencyHistogram current = new LatencyHistogram();
    private final LatencyHistogram previous = new LatencyHistogram();
    private volatile long windowStart = System.currentTimeMillis();

    public void record(long nanos) {
        cumulative.record(nanos);
        synchronized (current) {
            current.record(nanos);
        }
    }

    private void rotateIfDue() {
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MILLIS) {
            return;
        }
        synchronized (current) {
            if (now - windowStart < WINDOW_MILLIS) {
                return;
            }
            synchronized (previous) {
                previous.reset();
                previous.mergeFrom(current);
                current.reset();
            }
            windowStart = now;
        }
    }

    /** 当前窗口（上一窗口 + 本窗口）的百分位；窗口为空时回退到累计。 */
    public Snapshot snapshot() {
        rotateIfDue();
        LatencyHistogram window = new LatencyHistogram();
        synchronized (previous) {
            window.mergeFrom(previous);
        }
        synchronized (current) {
            window.mergeFrom(current);
        }
        // 只报告最近两个窗口的数据：没有流量时返回 0，不把历史尖峰带入当前读数
        LatencyHistogram source = window.count() > 0 ? window : cumulative;
        if (window.count() == 0) {
            return new Snapshot(0, 0, 0, 0, 0);
        }
        long max = source.maxMillis();
        // 分桶给的是桶上界，可能大于实际最大值：以 max 封顶，避免出现 P95 > max
        return new Snapshot(
                Math.min(source.percentileMillis(0.50), max),
                Math.min(source.percentileMillis(0.95), max),
                Math.min(source.percentileMillis(0.99), max),
                max,
                source.count());
    }

    public record Snapshot(double p50Millis, double p95Millis, double p99Millis,
                           long maxMillis, long samples) {
    }
}
