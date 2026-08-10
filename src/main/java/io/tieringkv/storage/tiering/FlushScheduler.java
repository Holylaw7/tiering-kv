package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.FlushManager;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALManager;

import java.util.concurrent.atomic.AtomicBoolean;

/** 异步 Flush 调度器（ADR-0020/0021）：后台执行、去重、失败保留重试。 */
public final class FlushScheduler {

    private final TierWorkerPool pool;
    private final MemTable memTable;
    private final WALManager wal;
    private final ColdStorageEngine cold;
    private final StorageMetrics metrics;
    private final AtomicBoolean flushInProgress = new AtomicBoolean();

    public FlushScheduler(
            TierWorkerPool pool,
            MemTable memTable,
            WALManager wal,
            ColdStorageEngine cold,
            StorageMetrics metrics) {
        this.pool = pool;
        this.memTable = memTable;
        this.wal = wal;
        this.cold = cold;
        this.metrics = metrics;
    }

    /** 触发一次后台 Flush；已在执行或入队失败返回 false。 */
    public boolean scheduleFlush() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return false;
        }
        pool.execute(() -> {
            try {
                long start = System.nanoTime();
                FlushManager.FlushStats stats = FlushManager.flush(memTable, wal, cold);
                metrics.recordFlush(stats.bytesFlushed(), System.nanoTime() - start);
            } catch (Exception e) {
                // Flush 失败：内存数据保留（immutable 语义），下次触发重试
            } finally {
                flushInProgress.set(false);
            }
        });
        return true;
    }
}
