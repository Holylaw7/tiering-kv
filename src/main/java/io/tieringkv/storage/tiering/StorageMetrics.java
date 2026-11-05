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
    /** 冷层表条目累加（含历史版本，用于键分布，非精确去重值）。 */
    private final AtomicLong coldEntryCount = new AtomicLong();

    // 后台任务进行中标记（TD-097）
    private final AtomicLong flushInProgress = new AtomicLong();
    private final AtomicLong compactionInProgress = new AtomicLong();

    // Compaction（TD-096）
    private final LongAdder compactionCount = new LongAdder();
    private final LongAdder compactionInputBytes = new LongAdder();
    private final LongAdder compactionOutputBytes = new LongAdder();
    private final LongAdder compactionLatencyNanos = new LongAdder();

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

    /** 记录冷层表条目累加值（TD-098）。 */
    public void setColdEntryCount(long entries) {
        coldEntryCount.set(entries);
    }

    /** 记录一次 SSTable 合并（输入/输出字节 + 耗时）。 */
    public void recordCompaction(long inputBytes, long outputBytes, long latencyNanos) {
        compactionCount.increment();
        compactionInputBytes.add(Math.max(0, inputBytes));
        compactionOutputBytes.add(Math.max(0, outputBytes));
        compactionLatencyNanos.add(Math.max(0, latencyNanos));
    }

    public void setFlushInProgress(boolean inProgress) {
        flushInProgress.set(inProgress ? 1 : 0);
    }

    public void setCompactionInProgress(boolean inProgress) {
        compactionInProgress.set(inProgress ? 1 : 0);
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
                coldSstableCount.get(), coldDiskUsage.get(), coldEntryCount.get(),
                compactionCount.sum(), compactionInputBytes.sum(),
                compactionOutputBytes.sum(), compactionLatencyNanos.sum(),
                flushInProgress.get(), compactionInProgress.get());
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
            long coldDiskUsage,
            long coldEntryCount,
            long compactionCount,
            long compactionInputBytes,
            long compactionOutputBytes,
            long compactionLatencyNanos,
            long flushInProgress,
            long compactionInProgress) {
    }
}