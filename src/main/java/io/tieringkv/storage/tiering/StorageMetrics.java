package io.tieringkv.storage.tiering;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 存储调度指标（ADR-0020）：内存 / 迁移 / Flush / 冷层。 */
public class StorageMetrics {

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

    /**
     * 冷层与后台任务的不可变快照（F3，ADR-0371）。
     *
     * <p>整份由刷新线程构建后一次性发布，INFO 只读一次引用，避免读到"半新半旧"的组合。
     */
    public record ColdSnapshot(long sstableCount, long diskUsageBytes, long entryCount,
                               long flushInProgress, long compactionInProgress,
                               long refreshedAtMillis) {
    }

    private volatile ColdSnapshot coldGauges = new ColdSnapshot(0, 0, 0, 0, 0, 0);

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

    /** 兼容旧调用：只替换记录中的对应字段，仍保持整份引用替换。 */
    public void setColdGauges(long sstableCount, long diskUsage) {
        ColdSnapshot old = coldGauges;
        coldGauges = new ColdSnapshot(sstableCount, diskUsage, old.entryCount(),
                old.flushInProgress(), old.compactionInProgress(), System.currentTimeMillis());
    }

    /** 一次性发布完整冷层快照（刷新线程调用）。 */
    public void publishColdSnapshot(long sstableCount, long diskUsage, long entryCount,
                                    long flushInProgress, long compactionInProgress) {
        coldGauges = new ColdSnapshot(sstableCount, diskUsage, entryCount,
                flushInProgress, compactionInProgress, System.currentTimeMillis());
    }

    public ColdSnapshot coldSnapshot() {
        return coldGauges;
    }

    /** 记录冷层表条目累加值（TD-098）。 */
    public void setColdEntryCount(long entries) {
        ColdSnapshot old = coldGauges;
        coldGauges = new ColdSnapshot(old.sstableCount(), old.diskUsageBytes(), entries,
                old.flushInProgress(), old.compactionInProgress(), System.currentTimeMillis());
    }

    /** 记录一次 SSTable 合并（输入/输出字节 + 耗时）。 */
    public void recordCompaction(long inputBytes, long outputBytes, long latencyNanos) {
        compactionCount.increment();
        compactionInputBytes.add(Math.max(0, inputBytes));
        compactionOutputBytes.add(Math.max(0, outputBytes));
        compactionLatencyNanos.add(Math.max(0, latencyNanos));
    }

    public void setFlushInProgress(boolean inProgress) {
        ColdSnapshot old = coldGauges;
        coldGauges = new ColdSnapshot(old.sstableCount(), old.diskUsageBytes(), old.entryCount(),
                inProgress ? 1 : 0, old.compactionInProgress(), System.currentTimeMillis());
    }

    public void setCompactionInProgress(boolean inProgress) {
        ColdSnapshot old = coldGauges;
        coldGauges = new ColdSnapshot(old.sstableCount(), old.diskUsageBytes(), old.entryCount(),
                old.flushInProgress(), inProgress ? 1 : 0, System.currentTimeMillis());
    }

    public long pendingMigrationCount() {
        return migrationPending.get();
    }

    public Snapshot snapshot() {
        ColdSnapshot cold = coldGauges;
        return new Snapshot(
                memoryUsedBytes.get(), memoryMaxBytes.get(), memoryEntryCount.get(),
                migrationPending.get(), migrationSuccess.sum(), migrationFailed.sum(),
                migrationLatencyNanos.sum(),
                flushCount.sum(), flushBytes.sum(), flushLatencyNanos.sum(),
                cold.sstableCount(), cold.diskUsageBytes(), cold.entryCount(),
                compactionCount.sum(), compactionInputBytes.sum(),
                compactionOutputBytes.sum(), compactionLatencyNanos.sum(),
                cold.flushInProgress(), cold.compactionInProgress());
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