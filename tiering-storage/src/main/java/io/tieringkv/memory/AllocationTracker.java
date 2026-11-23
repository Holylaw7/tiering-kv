package io.tieringkv.memory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 内存池统计（ADR-0027）：allocated / released / reuse / peak。 */
public final class AllocationTracker {

    private final LongAdder allocatedBytes = new LongAdder();
    private final LongAdder releasedBytes = new LongAdder();
    private final LongAdder reuseCount = new LongAdder();
    private final AtomicLong liveBytes = new AtomicLong();
    private final AtomicLong peakBytes = new AtomicLong();

    void recordAllocate(long bytes) {
        allocatedBytes.add(bytes);
        long live = liveBytes.addAndGet(bytes);
        peakBytes.accumulateAndGet(live, Math::max);
    }

    void recordRelease(long bytes) {
        releasedBytes.add(bytes);
        liveBytes.addAndGet(-bytes);
    }

    void recordReuse() {
        reuseCount.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(allocatedBytes.sum(), releasedBytes.sum(), reuseCount.sum(),
                liveBytes.get(), peakBytes.get());
    }

    public record Snapshot(
            long allocatedBytes,
            long releasedBytes,
            long reuseCount,
            long liveBytes,
            long peakBytes) {
    }
}
