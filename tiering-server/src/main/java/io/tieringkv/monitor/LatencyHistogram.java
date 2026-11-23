package io.tieringkv.monitor;

import java.util.concurrent.atomic.LongAdder;

/**
 * 命令延迟直方图（TD-096）。
 *
 * <p>固定上界分桶，桶上界即百分位报告的**上界值**（向下取整到桶），
 * 比"平均延迟"更能反映长尾；不做样本存储，内存占用恒定。
 *
 * <p>桶单位为微秒：50µs / 100µs / 250µs / 500µs / 1ms / 2.5ms / 5ms / 10ms /
 * 25ms / 50ms / 100ms / 250ms / 500ms / 1s / 2.5s / 5s / +∞。
 */
public final class LatencyHistogram {

    private static final long[] BOUNDS_MICROS = {
            50, 100, 250, 500,
            1_000, 2_500, 5_000, 10_000,
            25_000, 50_000, 100_000, 250_000,
            500_000, 1_000_000, 2_500_000, 5_000_000
    };

    private final LongAdder[] buckets = new LongAdder[BOUNDS_MICROS.length + 1];
    private final LongAdder total = new LongAdder();
    private final LongAdder maxNanos = new LongAdder();

    public LatencyHistogram() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void record(long nanos) {
        if (nanos < 0) {
            return;
        }
        long micros = nanos / 1_000;
        int index = buckets.length - 1;
        for (int i = 0; i < BOUNDS_MICROS.length; i++) {
            if (micros <= BOUNDS_MICROS[i]) {
                index = i;
                break;
            }
        }
        buckets[index].increment();
        total.increment();
        if (nanos > maxNanos.sum()) {
            maxNanos.add(nanos - maxNanos.sum());
        }
    }

    /** 把另一个直方图的计数并入本直方图（滚动窗口用）。 */
    public void mergeFrom(LatencyHistogram other) {
        if (other == null) {
            return;
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i].add(other.buckets[i].sum());
        }
        total.add(other.total.sum());
        long otherMax = other.maxNanos.sum();
        if (otherMax > maxNanos.sum()) {
            maxNanos.add(otherMax - maxNanos.sum());
        }
    }

    public long count() {
        return total.sum();
    }

    public long maxMillis() {
        return maxNanos.sum() / 1_000_000;
    }

    /**
     * 百分位（0..1），返回毫秒上界；空直方图返回 0。
     *
     * <p>例：p95 = 0.95，表示 95% 的样本耗时不超过该值。
     */
    public double percentileMillis(double percentile) {
        long samples = total.sum();
        if (samples == 0) {
            return 0;
        }
        double clamped = Math.min(1, Math.max(0, percentile));
        long target = Math.max(1, (long) Math.ceil(samples * clamped));
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                if (i >= BOUNDS_MICROS.length) {
                    return maxMillis();
                }
                return BOUNDS_MICROS[i] / 1000.0;
            }
        }
        return maxMillis();
    }

    public void reset() {
        for (LongAdder bucket : buckets) {
            bucket.reset();
        }
        total.reset();
        maxNanos.reset();
    }
}
