package io.tieringkv.transaction.tso;

import java.util.concurrent.atomic.AtomicLong;

/** TSO 服务（ADR-0216）：批量分配 + 单调 + 恢复不回退。 */
public final class TsoService {

    private final AtomicLong timestamp = new AtomicLong();
    private final AtomicLong watermark = new AtomicLong();

    /** 批量分配：返回 [start, end] 区间（含）。 */
    public long[] allocate(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "batch size must be positive");
        }
        long start = timestamp.getAndAdd(batchSize);
        long end = start + batchSize - 1;
        watermark.accumulateAndGet(end, Math::max);
        return new long[]{start, end};
    }

    /** 单分配。 */
    public long allocate() {
        return allocate(1)[0];
    }

    /** 恢复：只允许推进到更高水位（不回退）。 */
    public long restore(long persistedWatermark) {
        if (persistedWatermark < 0) {
            throw new IllegalArgumentException(
                    "watermark must be non-negative");
        }
        long current = watermark.accumulateAndGet(
                persistedWatermark, Math::max);
        // 新分配必须越过恢复水位：必要时推进分配游标，绝不回退。
        while (true) {
            long allocated = timestamp.get();
            if (allocated > current) {
                return current;
            }
            if (timestamp.compareAndSet(allocated, current + 1)) {
                return current;
            }
        }
    }

    public long watermark() {
        return watermark.get();
    }

    public long currentTimestamp() {
        return timestamp.get();
    }
}
