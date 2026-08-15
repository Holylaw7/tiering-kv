package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.FlushManager;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemTableManager;
import io.tieringkv.storage.wal.WALManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 异步 Flush 调度器（ADR-0020/0021）：后台执行、去重、失败保留重试。 */
public final class FlushScheduler {

    private final TierWorkerPool pool;
    private final MemTable memTable;
    private final MemTableManager manager;
    private final WALManager wal;
    private final ColdStorageEngine cold;
    private final StorageMetrics metrics;
    private final AdaptiveFlushController controller;
    private final ScheduledExecutorService autoFlusher;
    private final AtomicBoolean flushInProgress = new AtomicBoolean();

    public FlushScheduler(
            TierWorkerPool pool,
            MemTable memTable,
            WALManager wal,
            ColdStorageEngine cold,
            StorageMetrics metrics) {
        this(pool, memTable, wal, cold, metrics, null);
    }

    public FlushScheduler(
            TierWorkerPool pool,
            MemTable memTable,
            WALManager wal,
            ColdStorageEngine cold,
            StorageMetrics metrics,
            AdaptiveFlushController controller) {
        this.pool = pool;
        this.memTable = memTable;
        this.manager = null;
        this.wal = wal;
        this.cold = cold;
        this.metrics = metrics;
        this.controller = controller;
        this.autoFlusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "adaptive-flush");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** MemTableManager 模式（ADR-0324 生产接入）：写入不停顿的轮转 flush。 */
    public FlushScheduler(
            TierWorkerPool pool,
            MemTableManager manager,
            WALManager wal,
            ColdStorageEngine cold,
            StorageMetrics metrics) {
        this(pool, manager, wal, cold, metrics, null);
    }

    public FlushScheduler(
            TierWorkerPool pool,
            MemTableManager manager,
            WALManager wal,
            ColdStorageEngine cold,
            StorageMetrics metrics,
            AdaptiveFlushController controller) {
        this.pool = pool;
        this.memTable = null;
        this.manager = manager;
        this.wal = wal;
        this.cold = cold;
        this.metrics = metrics;
        this.controller = controller;
        this.autoFlusher = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "adaptive-flush-manager");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 启动自适应自动巡检（ADR-0049）：按控制器动态间隔检查内存水位。 */
    public void startAutoFlush() {
        if (controller == null) {
            return;
        }
        autoFlusher.scheduleWithFixedDelay(this::autoFlushTick,
                controller.flushIntervalMillis(),
                controller.flushIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void autoFlushTick() {
        long max = manager != null
                ? manager.active().memoryManager().maxBytes()
                : memTable.memoryManager().maxBytes();
        if (max <= 0) {
            return;
        }
        double used = manager != null
                ? manager.active().memoryManager().usedBytes()
                : memTable.memoryManager().usedBytes();
        double ratio = used / max;
        if (controller.shouldAutoFlush(ratio)) {
            scheduleFlush();
        }
    }

    public AdaptiveFlushController controller() {
        return controller;
    }

    public void close() {
        autoFlusher.shutdownNow();
    }

    /** 触发一次后台 Flush；已在执行或入队失败返回 false。 */
    public boolean scheduleFlush() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return false;
        }
        pool.execute(() -> {
            try {
                long start = System.nanoTime();
                if (manager != null) {
                    if (manager.immutableCount() == 0
                            && manager.active().size() > 0) {
                        manager.rotate();
                    }
                    java.util.Optional<MemTable> flushed =
                            manager.flushOldest(cold);
                    if (flushed.isPresent()) {
                        metrics.recordFlush(flushed.get().size(),
                                System.nanoTime() - start);
                    }
                } else {
                    FlushManager.FlushStats stats =
                            FlushManager.flush(memTable, wal, cold);
                    metrics.recordFlush(stats.bytesFlushed(),
                            System.nanoTime() - start);
                }
            } catch (Exception e) {
                // Flush 失败：内存数据保留（immutable 语义），下次触发重试
            } finally {
                flushInProgress.set(false);
            }
        });
        return true;
    }
}
