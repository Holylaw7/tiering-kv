package io.tieringkv.transaction.tso;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨云授时仲裁 + 防时钟回拨（ADR-0237）：多数云时间共识（中位数 +
 * 容差过滤）+ 单调计数器 + 最大回拨窗口告警。
 */
public final class CrossCloudTsoArbitration {

    /** 云时间源。 */
    public record CloudTimeSource(String cloud,
                                  long timestampMillis) {
    }

    /** 回拨事件。 */
    public record RollbackEvent(String cloud,
                                long observedMillis,
                                long monotonicMillis,
                                long timestampMillis) {
    }

    private final List<CloudTimeSource> sources =
            new CopyOnWriteArrayList<>();
    private final long maxSkewMillis;
    private final long maxRollbackMillis;
    private final AtomicLong lastTimestamp =
            new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastArbitrated =
            new AtomicLong(Long.MIN_VALUE);
    private final List<RollbackEvent> rollbackEvents =
            new CopyOnWriteArrayList<>();
    private volatile boolean frozen;

    public CrossCloudTsoArbitration(
            List<CloudTimeSource> sources,
            long maxSkewMillis, long maxRollbackMillis) {
        if (sources == null
                || maxSkewMillis < 0 || maxRollbackMillis < 0) {
            throw new IllegalArgumentException(
                    "sources required (may be empty) and windows "
                            + "must be "
                            + "non-negative");
        }
        this.sources.addAll(sources);
        this.maxSkewMillis = maxSkewMillis;
        this.maxRollbackMillis = maxRollbackMillis;
    }

    /** 多数云仲裁时间：中位数校准（丢弃偏离 > maxSkew 的源）。 */
    public long arbitrate() {
        if (sources.isEmpty()) {
            long last = lastTimestamp.get();
            return last == Long.MIN_VALUE ? 0 : last;
        }
        long[] times = sources.stream()
                .mapToLong(CloudTimeSource::timestampMillis)
                .sorted().toArray();
        long median = median(times);
        long[] trusted = Arrays.stream(times)
                .filter(time -> Math.abs(time - median)
                        <= maxSkewMillis)
                .toArray();
        return trusted.length > 0 ? median(trusted) : median;
    }

    /** 单调时间戳 + 回拨保护：超过窗口触发冻结与告警。 */
    public long timestamp() {
        long now = arbitrate();
        long lastArb = lastArbitrated.get();
        if (lastArb != Long.MIN_VALUE
                && now < lastArb - maxRollbackMillis) {
            frozen = true;
            rollbackEvents.add(new RollbackEvent(
                    "arbitrated", now, lastArb,
                    System.currentTimeMillis()));
        }
        lastArbitrated.accumulateAndGet(now, Math::max);
        while (true) {
            long current = lastTimestamp.get();
            long candidate = current == Long.MIN_VALUE
                    ? now : Math.max(now, current + 1);
            if (candidate <= current) {
                return current;
            }
            if (lastTimestamp.compareAndSet(current,
                    candidate)) {
                return candidate;
            }
        }
    }

    public long restore(long persistedWatermark) {
        if (persistedWatermark < 0) {
            throw new IllegalArgumentException(
                    "watermark must be non-negative");
        }
        lastTimestamp.accumulateAndGet(persistedWatermark,
                Math::max);
        return persistedWatermark;
    }

    public boolean frozen() {
        return frozen;
    }

    public void unfreeze() {
        frozen = false;
    }

    public List<RollbackEvent> rollbackEvents() {
        return List.copyOf(rollbackEvents);
    }

    public List<CloudTimeSource> sources() {
        return List.copyOf(sources);
    }

    /** 动态注册授时源（跨云拓扑变化时更新）。 */
    public void addSource(CloudTimeSource source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "source required");
        }
        sources.add(source);
    }

    /** 清空授时源（拓扑迁移 / 故障隔离）。 */
    public void clearSources() {
        sources.clear();
    }

    private static long median(long[] sorted) {
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
