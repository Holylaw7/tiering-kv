package io.tieringkv.storage.tiering;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 存储调度指标（ADR-0020）：内存 / 迁移 / Flush / 冷层。 */
public final class StorageMetrics {

    // 内存（gauge，由控制器刷新）
    private final AtomicLong memoryUsedBytes = new AtomicLong();
    private final AtomicLong memoryMaxBytes = new AtomicLong();
    private final AtomicLong memoryEntryCount = new AtomicLong();

    // 迁移
    private final AtomicLong migrationPending = new AtomicLong();
    private final LongAdder migrationSuccess = new LongAdder();
    private final LongAdder migrationFailed = new LongAdder();
    private final LongAdder migrationLatencyNanos = new LongAdder();

    // Flush
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder flushBytes = new LongAdder();
    private final LongAdder flushLatencyNanos = new LongAdder();

    // 冷层（gauge）
    private final AtomicLong coldSstableCount = new AtomicLong();
    private final AtomicLong coldDiskUsage = new AtomicLong();

    public void setMemoryGauges(long usedBytes, long maxBytes, long entryCount) {
        memoryUsedBytes.set(usedBytes);
        memoryMaxBytes.set(maxBytes);
        memoryEntryCount.set(entryCount);
    }

    public void migrationSubmitted() {
        migrationPending.incrementAndGet();
    }

    public void migrationCompleted(boolean success, long latencyNanos) {
        migrationPending.decrementAndGet();
        migrationLatencyNanos.add(latencyNanos);
        if (success) {
            migrationSuccess.increment();
        } else {
            migrationFailed.increment();
        }
    }

    public void recordFlush(long bytes, long latencyNanos) {
        flushCount.increment();
        flushBytes.add(bytes);
        flushLatencyNanos.add(latencyNanos);
    }

    public void setColdGauges(long sstableCount, long diskUsage) {
        coldSstableCount.set(sstableCount);
        coldDiskUsage.set(diskUsage);
    }

    public long pendingMigrationCount() {
        return migrationPending.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                memoryUsedBytes.get(), memoryMaxBytes.get(), memoryEntryCount.get(),
                migrationPending.get(), migrationSuccess.sum(), migrationFailed.sum(),
                migrationLatencyNanos.sum(),
                flushCount.sum(), flushBytes.sum(), flushLatencyNanos.sum(),
                coldSstableCount.get(), coldDiskUsage.get());
    }

    public record Snapshot(
            long memoryUsedBytes,
            long memoryMaxBytes,
            long memoryEntryCount,
            long migrationPending,
            long migrationSuccess,
            long migrationFailed,
            long migrationLatencyNanos,
            long flushCount,
            long flushBytes,
            long flushLatencyNanos,
            long coldSstableCount,
            long coldDiskUsage) {
    }
}
