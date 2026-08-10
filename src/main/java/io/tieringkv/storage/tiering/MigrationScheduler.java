package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 异步迁移调度器（ADR-0020/0022）：入队（inFlight 去重）→ worker 执行
 * （冷层写入 → WAL DELETE → 版本守卫删内存）→ SUCCESS；失败重试上限后 FAILED。
 */
public final class MigrationScheduler implements AutoCloseable {

    private final TierWorkerPool pool;
    private final MigrationLog log;
    private final ColdStorageEngine cold;
    private final WALManager wal;
    private final MemTable memTable;
    private final StorageMetrics metrics;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final Set<ByteBuffer> inFlight = ConcurrentHashMap.newKeySet();
    private final Object stateMonitor = new Object();
    private volatile Consumer<MigrationTask> completionListener;

    public MigrationScheduler(
            TierWorkerPool pool,
            MigrationLog log,
            ColdStorageEngine cold,
            WALManager wal,
            MemTable memTable,
            StorageMetrics metrics,
            int maxRetries,
            long retryBackoffMillis) {
        this.pool = pool;
        this.log = log;
        this.cold = cold;
        this.wal = wal;
        this.memTable = memTable;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /** 入队迁移任务；同键已排队返回 false；日志失败抛 IllegalStateException。 */
    public boolean submit(MigrationTask task) {
        if (!inFlight.add(ByteBuffer.wrap(task.key()))) {
            return false;
        }
        metrics.migrationSubmitted();
        try {
            log.append(task.withStatus(MigrationTask.Status.PENDING));
        } catch (IOException e) {
            inFlight.remove(ByteBuffer.wrap(task.key()));
            metrics.migrationCompleted(false, 0);
            throw new IllegalStateException("migration log append failed", e);
        }
        pool.execute(() -> run(task));
        return true;
    }

    public boolean contains(byte[] key) {
        return inFlight.contains(ByteBuffer.wrap(key));
    }

    public long pendingCount() {
        return metrics.snapshot().migrationPending();
    }

    public void setCompletionListener(Consumer<MigrationTask> listener) {
        this.completionListener = listener;
    }

    /** 启动恢复：重放未完成任务（幂等）；返回已恢复（重新入队）的任务。 */
    public List<MigrationTask> recoverAndResume() throws IOException {
        List<MigrationTask> unfinished = log.recover();
        List<MigrationTask> resumed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (MigrationTask task : unfinished) {
            KeyValueEntry current = memTable.getEntry(task.key());
            if (current == null || current.version() != task.version() || !current.isLive(now)) {
                continue; // 已迁移/已删除：跳过
            }
            MigrationTask hydrated = MigrationTask.pending(current, task.source(), task.target());
            if (submit(hydrated)) {
                resumed.add(hydrated);
            }
        }
        log.compact(resumed);
        return resumed;
    }

    private void run(MigrationTask initial) {
        long startNanos = System.nanoTime();
        boolean success = false;
        MigrationTask finalTask = initial;
        try {
            MigrationTask current = initial;
            int attempts = 0;
            while (true) {
                try {
                    log.append(current.withStatus(MigrationTask.Status.RUNNING));
                    cold.put(current.entry());
                    wal.append(WALEntry.delete(System.currentTimeMillis(), current.key(), 0));
                    memTable.removePhysicalIfVersion(current.key(), current.version());
                    finalTask = current.withStatus(MigrationTask.Status.SUCCESS);
                    log.append(finalTask);
                    success = true;
                    break;
                } catch (Exception e) {
                    attempts++;
                    if (attempts > maxRetries) {
                        finalTask = current.withStatus(MigrationTask.Status.FAILED);
                        try {
                            log.append(finalTask);
                        } catch (IOException ignored) {
                            // 日志失败：内存保留，数据安全
                        }
                        break;
                    }
                    current = current.withRetryCount(attempts).withStatus(MigrationTask.Status.RETRY);
                    try {
                        log.append(current);
                    } catch (IOException ignored) {
                    }
                    if (retryBackoffMillis > 0) {
                        try {
                            Thread.sleep(retryBackoffMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } finally {
            inFlight.remove(ByteBuffer.wrap(initial.key()));
            metrics.migrationCompleted(success, System.nanoTime() - startNanos);
            synchronized (stateMonitor) {
                stateMonitor.notifyAll();
            }
            Consumer<MigrationTask> listener = completionListener;
            if (listener != null) {
                listener.accept(finalTask);
            }
        }
    }

    public Object stateMonitor() {
        return stateMonitor;
    }

    @Override
    public void close() throws IOException {
        log.close();
    }
}
