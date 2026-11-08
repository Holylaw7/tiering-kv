package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 冷层 Gauge 刷新器（F3 / ADR-0371）。
 *
 * <p>把 `tablesSnapshot()` 与按表求和这类 O(表数) 的工作从写路径挪到独立线程：
 * 单线程、固定周期、禁止重叠、失败保留上一份快照。与 F1 的冷层计数刷新器**相互独立**，
 * 任一失败都不影响另一个。
 */
public final class StorageGaugeRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StorageGaugeRefresher.class);

    private final ColdStorageEngine cold;
    private final StorageMetrics metrics;
    private final BooleanSupplier flushInProgress;
    private final long intervalMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong refreshCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong lastDurationNanos = new AtomicLong();
    private final AtomicBoolean refreshRequested = new AtomicBoolean(true);
    private volatile boolean closed;
    private Thread thread;

    public StorageGaugeRefresher(ColdStorageEngine cold, StorageMetrics metrics,
                                 BooleanSupplier flushInProgress) {
        this(cold, metrics, flushInProgress, 500L);
    }

    public StorageGaugeRefresher(ColdStorageEngine cold, StorageMetrics metrics,
                                 BooleanSupplier flushInProgress, long intervalMillis) {
        if (cold == null || metrics == null) {
            throw new IllegalArgumentException("cold and metrics required");
        }
        this.cold = cold;
        this.metrics = metrics;
        this.flushInProgress = flushInProgress == null ? () -> false : flushInProgress;
        this.intervalMillis = Math.max(50L, intervalMillis);
    }

    /** 启动刷新线程；重复调用无效。 */
    public synchronized void start() {
        if (thread != null || closed) {
            return;
        }
        Thread worker = new Thread(this::loop, "storage-gauge-refresher");
        worker.setDaemon(true);
        this.thread = worker;
        worker.start();
    }

    private void loop() {
        while (!closed) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) {
                return;
            }
            if (!refreshRequested.getAndSet(false)) {
                continue;
            }
            refreshOnce();
        }
    }

    /** 请求一次刷新（下一个周期执行）；写路径/合并事件可安全调用。 */
    public void requestRefresh() {
        refreshRequested.set(true);
    }

    /**
     * 立即执行一次刷新。
     *
     * <p>用 CAS 保证同一时刻只有一次刷新在跑；并发调用直接返回，不排队、不阻塞。
     */
    public void refreshOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            List<SSTableMeta> tables = cold.tablesSnapshot();
            long count = tables.size();
            long diskUsage = 0;
            long entries = 0;
            for (SSTableMeta meta : tables) {
                diskUsage += meta.fileSize();
                entries += meta.entryCount();
            }
            metrics.publishColdSnapshot(count, diskUsage, entries,
                    flushInProgress.getAsBoolean() ? 1 : 0, cold.isCompacting() ? 1 : 0,
                    cold.pendingCount(), cold.pendingByteSize());
            refreshCount.incrementAndGet();
        } catch (RuntimeException ex) {
            // 失败保留上一份有效快照，并计数，不静默
            failureCount.incrementAndGet();
            log.warn("冷层 gauge 刷新失败，保留上一份快照", ex);
        } finally {
            lastDurationNanos.set(System.nanoTime() - startedAt);
            running.set(false);
        }
    }

    public long refreshCount() {
        return refreshCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long lastDurationNanos() {
        return lastDurationNanos.get();
    }

    /** 关闭：幂等，等待在跑的刷新结束（最多 1 秒），不吞异常。 */
    @Override
    public void close() {
        closed = true;
        Thread worker;
        synchronized (this) {
            worker = thread;
            thread = null;
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}