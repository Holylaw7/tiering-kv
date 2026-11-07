package io.tieringkv.monitor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 延迟统计（TD-096 / F4 ADR-0373）：最近 60 秒窗口 + 自启动累计。
 *
 * <p>F4 改动：`record()` 不再获取任何监视器；当前窗口是一个 {@link AtomicReference}，
 * 轮转时才加锁。直方图内部用 LongAdder，因此并发写入安全。
 *
 * <p>快照语义：**近似一致**——合并期间的新写入可能落在上一窗口或被下一窗口看到，
 * 但每个样本恰好计入一次累计值；分位数仍是固定桶上界估算，与旧实现一致。
 */
public final class LatencyStats {

    /** 窗口时长：60 秒。 */
    static final long WINDOW_MILLIS = 60_000L;

    private final LatencyHistogram cumulative = new LatencyHistogram();
    private final Object rotateLock = new Object();
    private final AtomicReference<LatencyHistogram> current =
            new AtomicReference<>(new LatencyHistogram());
    private final AtomicReference<LatencyHistogram> previous =
            new AtomicReference<>(new LatencyHistogram());
    private volatile long windowStart = System.currentTimeMillis();

    public void record(long nanos) {
        cumulative.record(nanos);
        // 无锁：直方图内部为 LongAdder；轮转只替换引用，不阻塞写入
        current.get().record(nanos);
    }

    private void rotateIfDue() {
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MILLIS) {
            return;
        }
        synchronized (rotateLock) {
            if (now - windowStart < WINDOW_MILLIS) {
                return;
            }
            LatencyHistogram finished = current.getAndSet(new LatencyHistogram());
            previous.set(finished);
            windowStart = now;
        }
    }

    /** 当前窗口（上一窗口 + 本窗口）的百分位；窗口为空时返回 0。 */
    public Snapshot snapshot() {
        rotateIfDue();
        LatencyHistogram window = new LatencyHistogram();
        window.mergeFrom(previous.get());
        window.mergeFrom(current.get());
        if (window.count() == 0) {
            return new Snapshot(0, 0, 0, 0, 0);
        }
        long max = window.maxMillis();
        return new Snapshot(
                Math.min(window.percentileMillis(0.50), max),
                Math.min(window.percentileMillis(0.95), max),
                Math.min(window.percentileMillis(0.99), max),
                max,
                window.count());
    }

    public record Snapshot(double p50Millis, double p95Millis, double p99Millis,
                           long maxMillis, long samples) {
    }
}
