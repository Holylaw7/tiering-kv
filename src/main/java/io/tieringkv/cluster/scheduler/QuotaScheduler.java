package io.tieringkv.cluster.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/** 调度配额（ADR-0205）：限流。 */
public final class QuotaScheduler {

    private final AtomicLong quota = new AtomicLong();
    private final AtomicLong used = new AtomicLong();

    public QuotaScheduler(long quota) {
        setQuota(quota);
    }

    public void setQuota(long quota) {
        if (quota < 0) {
            throw new IllegalArgumentException(
                    "quota must be non-negative");
        }
        this.quota.set(quota);
    }

    public boolean tryAcquire() {
        while (true) {
            long current = used.get();
            if (current >= quota.get()) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public long remaining() {
        return Math.max(0, quota.get() - used.get());
    }

    public long used() {
        return used.get();
    }

    public void reset() {
        used.set(0);
    }
}
