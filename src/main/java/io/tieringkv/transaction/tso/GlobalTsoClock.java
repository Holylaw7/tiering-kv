package io.tieringkv.transaction.tso;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全球统一时钟（ADR-0230）：GPS/原子钟/NTP 混合授时源抽象 + 中位数校准 +
 * 单调推进 + 恢复不回退。
 */
public final class GlobalTsoClock {

    /** 授时源类型。 */
    public enum TimeSourceType {
        GPS,
        ATOMIC,
        NTP,
        SIMULATED
    }

    /** 授时源。 */
    public record TimeSource(TimeSourceType type,
                             long timestampMillis) {
    }

    private final List<TimeSource> sources =
            new CopyOnWriteArrayList<>();
    private final long maxSkewMillis;
    private final AtomicLong lastTimestamp =
            new AtomicLong(Long.MIN_VALUE);

    public GlobalTsoClock(List<TimeSource> sources,
                          long maxSkewMillis) {
        if (sources == null || sources.isEmpty()
                || maxSkewMillis < 0) {
            throw new IllegalArgumentException(
                    "sources required and maxSkew must be "
                            + "non-negative");
        }
        this.sources.addAll(sources);
        this.maxSkewMillis = maxSkewMillis;
    }

    /** 中位数校准：丢弃偏离中位数超过 maxSkew 的源后取中位数。 */
    public long now() {
        long[] times = sources.stream()
                .mapToLong(TimeSource::timestampMillis)
                .sorted().toArray();
        long median = median(times);
        long[] trusted = Arrays.stream(times)
                .filter(time -> Math.abs(time - median)
                        <= maxSkewMillis)
                .toArray();
        return trusted.length > 0 ? median(trusted) : median;
    }

    /** 单调时间戳：max(校准时间, 上次 + 1)，绝不回拨。 */
    public long timestamp() {
        while (true) {
            long current = lastTimestamp.get();
            long candidate = Math.max(now(),
                    current == Long.MIN_VALUE
                            ? Long.MIN_VALUE : current + 1);
            if (candidate <= current) {
                return current;
            }
            if (lastTimestamp.compareAndSet(current,
                    candidate)) {
                return candidate;
            }
        }
    }

    /** 恢复：推进单调计数器越过水位，返回已恢复水位。 */
    public long restore(long persistedWatermark) {
        if (persistedWatermark < 0) {
            throw new IllegalArgumentException(
                    "watermark must be non-negative");
        }
        lastTimestamp.accumulateAndGet(persistedWatermark,
                Math::max);
        return persistedWatermark;
    }

    public List<TimeSource> sources() {
        return List.copyOf(sources);
    }

    public long maxSkewMillis() {
        return maxSkewMillis;
    }

    private static long median(long[] sorted) {
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
