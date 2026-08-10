package io.tieringkv.mvcc;

import java.util.concurrent.atomic.AtomicLong;

/** 单调时间戳 Oracle（ADR-0072）：并发无重复，支持批量。 */
public final class TimestampOracle {

    private final AtomicLong next = new AtomicLong(new HybridLogicalClock().now());

    public long nextTimestamp() {
        return next.getAndIncrement();
    }

    /** 分配 n 个连续时间戳，返回首个（[base, base+n)）。 */
    public long nextBatch(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        long base = next.getAndAdd(n);
        if (base > Long.MAX_VALUE - n) {
            throw new IllegalStateException("timestamp overflow");
        }
        return base;
    }

    public long peek() {
        return next.get();
    }

    /** 恢复：从持久化时间戳继续（不回退）。 */
    public void recover(long lastTimestamp) {
        next.accumulateAndGet(lastTimestamp + 1, Math::max);
    }
}
