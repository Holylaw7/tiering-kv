package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
    private final int maxPending;
    private final int minWorkers;
    private final int maxWorkers;
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
        this(pool, log, cold, wal, memTable, metrics, maxRetries,
                retryBackoffMillis, 0, -1, -1);
    }

    /** 增强构造（ADR-0325）：maxPending<=0 表示无限制；maxWorkers<=0
     *  表示不启用动态 worker。 */
    public MigrationScheduler(
            TierWorkerPool pool,
            MigrationLog log,
            ColdStorageEngine cold,
            WALManager wal,
            MemTable memTable,
            StorageMetrics metrics,
            int maxRetries,
            long retryBackoffMillis,
            int maxPending,
            int minWorkers,
            int maxWorkers) {
        this.pool = pool;
        this.log = log;
        this.cold = cold;
        this.wal = wal;
        this.memTable = memTable;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
        this.maxPending = maxPending;
        this.minWorkers = minWorkers;
        this.maxWorkers = maxWorkers;
    }

    /** 入队迁移任务；同键已排队返回 false；日志失败抛 IllegalStateException。 */
    public boolean submit(MigrationTask task) {
        if (maxPending > 0 && inFlight.size() >= maxPending) {
            return false; // 准入拒绝：调用方背压/重试
        }
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
        adjustWorkers();
        return true;
    }

    /** 批量迁移（ADR-0325）：冷层一次 writeTable，逐条 WAL/内存删除。 */
    public int submitBatch(List<MigrationTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        List<MigrationTask> accepted = new ArrayList<>();
        for (MigrationTask task : tasks) {
            if (maxPending > 0
                    && inFlight.size() + accepted.size() >= maxPending) {
                break;
            }
            if (inFlight.add(ByteBuffer.wrap(task.key()))) {
                accepted.add(task);
            }
        }
        if (accepted.isEmpty()) {
            return 0;
        }
        for (MigrationTask task : accepted) {
            metrics.migrationSubmitted();
            try {
                log.append(task.withStatus(MigrationTask.Status.PENDING));
            } catch (IOException e) {
                inFlight.remove(ByteBuffer.wrap(task.key()));
                metrics.migrationCompleted(false, 0);
                throw new IllegalStateException(
                        "migration log append failed", e);
            }
        }
        pool.execute(() -> runBatch(accepted));
        adjustWorkers();
        return accepted.size();
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

    private void runBatch(List<MigrationTask> tasks) {
        long startNanos = System.nanoTime();
        try {
            // SSTable 要求 key 有序：按字典序排序后一次写表
            List<KeyValueEntry> entries = tasks.stream()
                    .map(MigrationTask::entry)
                    .sorted(Comparator.comparing(
                            KeyValueEntry::key, Arrays::compare))
                    .collect(Collectors.toList());
            cold.writeTable(entries);
            long now = System.currentTimeMillis();
            long elapsed = (System.nanoTime() - startNanos)
                    / Math.max(1, tasks.size());
            for (MigrationTask task : tasks) {
                wal.append(WALEntry.delete(now, task.key(), 0));
                memTable.removePhysicalIfVersion(task.key(),
                        task.version());
                log.append(task.withStatus(MigrationTask.Status.SUCCESS));
                inFlight.remove(ByteBuffer.wrap(task.key()));
                metrics.migrationCompleted(true, elapsed);
            }
        } catch (Exception e) {
            // 批量冷层写失败：逐条回退到单任务重试路径（幂等可恢复）
            for (MigrationTask task : tasks) {
                pool.execute(() -> run(task));
            }
        } finally {
            adjustWorkers();
        }
    }

    /** 动态 worker 水位调整（滞回）：pending 高扩、低缩。 */
    private void adjustWorkers() {
        if (maxWorkers <= 0 || minWorkers <= 0) {
            return;
        }
        int pending = inFlight.size();
        int current = pool.workers();
        int target = pending > maxWorkers * 2
                ? maxWorkers
                : pending <= minWorkers ? minWorkers : current;
        if (target != current) {
            pool.adjust(target);
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
