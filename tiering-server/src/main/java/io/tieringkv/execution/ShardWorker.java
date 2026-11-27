package io.tieringkv.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分片 worker（ADR-0023 / B1 优化 + v6.5.0 Phase 2 连续写批聚合）：
 * 单线程批拉取消费分片队列；相邻 {@link BatchableShardTask} 合并为单次 WAL/存储批量提交，
 * 遇读命令或非批写命令立即刷出前序写段以严格保持同键线性一致性；异常隔离。
 */
final class ShardWorker implements Runnable {

    private static final int MAX_DRAIN_BATCH = 64;

    private final int shardId;
    private final ShardQueue queue;
    private final AtomicBoolean closed;
    private final int maxWriteBatchEntries;
    private final AtomicLong completed = new AtomicLong();
    private long localCompleted;

    ShardWorker(ShardQueue queue, AtomicBoolean closed) {
        this(0, queue, closed, MAX_DRAIN_BATCH);
    }

    ShardWorker(int shardId, ShardQueue queue, AtomicBoolean closed, int maxWriteBatchEntries) {
        this.shardId = shardId;
        this.queue = queue;
        this.closed = closed;
        this.maxWriteBatchEntries = Math.max(1, Math.min(maxWriteBatchEntries, MAX_DRAIN_BATCH));
    }

    long completedCount() {
        return completed.getAcquire();
    }

    @Override
    public void run() {
        List<Runnable> drained = new ArrayList<>(MAX_DRAIN_BATCH);
        List<BatchableShardTask> batchScratch = new ArrayList<>(maxWriteBatchEntries);
        while (!closed.get()) {
            Runnable first;
            try {
                first = queue.poll(200);
            } catch (InterruptedException e) {
                if (closed.get()) {
                    return;
                }
                continue;
            }
            if (first == null) {
                continue;
            }
            drained.add(first);
            queue.drainTo(drained, MAX_DRAIN_BATCH - 1);
            executeDrained(drained, batchScratch);
            drained.clear();
        }
    }

    private void executeDrained(List<Runnable> drained, List<BatchableShardTask> batchScratch) {
        int n = drained.size();
        if (n == 1 || maxWriteBatchEntries <= 1) {
            for (int i = 0; i < n; i++) {
                runIsolated(drained.get(i));
            }
            return;
        }
        int i = 0;
        while (i < n) {
            Runnable task = drained.get(i);
            if (task instanceof BatchableShardTask first) {
                Object key = first.batchKey();
                int j = i + 1;
                while (j < n && (j - i) < maxWriteBatchEntries) {
                    Runnable next = drained.get(j);
                    if (next instanceof BatchableShardTask bt && bt.batchKey() == key) {
                        j++;
                    } else {
                        break;
                    }
                }
                if (j - i > 1) {
                    batchScratch.clear();
                    for (int k = i; k < j; k++) {
                        batchScratch.add((BatchableShardTask) drained.get(k));
                    }
                    runBatchIsolated(first, batchScratch);
                    batchScratch.clear();
                    i = j;
                    continue;
                }
            }
            runIsolated(task);
            i++;
        }
    }

    private void runBatchIsolated(BatchableShardTask first, List<BatchableShardTask> batch) {
        int count = batch.size();
        try {
            first.runBatch(shardId, batch);
        } catch (Throwable ignored) {
            // 批执行兜底异常隔离：不导致 worker 线程退出
        } finally {
            localCompleted += count;
            completed.setRelease(localCompleted);
        }
    }

    private void runIsolated(Runnable task) {
        try {
            task.run();
        } catch (Throwable ignored) {
            // 分片 worker 异常隔离：不导致 server 退出
        } finally {
            completed.setRelease(++localCompleted);
        }
    }
}
