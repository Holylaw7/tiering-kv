package io.tieringkv.execution;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Key 分片执行器（ADR-0023 / B1 优化 + v6.5.0 Phase 2 写批聚合透传）：
 * {@code hash(key) % N} 路由，每分片单 worker——同键 FIFO 有序、异键并行；
 * 每分片独立 {@link LongAdder} 提交计数 + 单写者完成计数，零全局原子争用且保证 {@link #awaitIdle} 无竞态窗口。
 */
public final class KeyShardExecutor implements AutoCloseable {

    private static final int DEFAULT_MAX_WRITE_BATCH = 64;

    private final int shardCount;
    private final ShardQueue[] queues;
    private final ShardWorker[] workers;
    private final LongAdder[] submittedPerShard;
    private final Thread[] threads;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrencyMetrics metrics = new ConcurrencyMetrics();

    public KeyShardExecutor(int shardCount, String name) {
        this(shardCount, name, DEFAULT_MAX_WRITE_BATCH);
    }

    public KeyShardExecutor(int shardCount, String name, int maxWriteBatchEntries) {
        this.shardCount = Math.max(1, shardCount);
        this.queues = new ShardQueue[this.shardCount];
        this.workers = new ShardWorker[this.shardCount];
        this.submittedPerShard = new LongAdder[this.shardCount];
        this.threads = new Thread[this.shardCount];
        for (int i = 0; i < this.shardCount; i++) {
            queues[i] = new ShardQueue();
            workers[i] = new ShardWorker(i, queues[i], closed, maxWriteBatchEntries);
            submittedPerShard[i] = new LongAdder();
            Thread thread = new Thread(workers[i], name + "-" + i);
            thread.setDaemon(true);
            threads[i] = thread;
            thread.start();
        }
    }

    public boolean submit(byte[] key, Runnable task) {
        if (closed.get()) {
            return false;
        }
        int shard = ShardRouter.route(key, shardCount);
        long submitted = System.nanoTime();
        submittedPerShard[shard].increment();
        Runnable wrapped = (task instanceof BatchableShardTask batchable)
                ? new TrackedBatchTask(batchable, submitted, metrics)
                : new TrackedTask(task, submitted, metrics);
        boolean offered = queues[shard].offer(wrapped);
        if (!offered) {
            submittedPerShard[shard].decrement();
            return false;
        }
        metrics.taskSubmitted(queues[shard].size());
        return true;
    }

    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean idle = true;
            for (int i = 0; i < shardCount; i++) {
                long submitted = submittedPerShard[i].sum();
                if (workers[i].completedCount() < submitted || queues[i].size() > 0) {
                    idle = false;
                    break;
                }
            }
            if (idle) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    public int shardCount() {
        return shardCount;
    }

    public ConcurrencyMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        closed.set(true);
        for (Thread thread : threads) {
            thread.interrupt();
        }
    }

    /** 轻量单层任务对象，替代双层捕获 Lambda。 */
    private static final class TrackedTask implements Runnable {
        private final Runnable delegate;
        private final long submittedNanos;
        private final ConcurrencyMetrics metrics;

        TrackedTask(Runnable delegate, long submittedNanos, ConcurrencyMetrics metrics) {
            this.delegate = delegate;
            this.submittedNanos = submittedNanos;
            this.metrics = metrics;
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            metrics.taskStarted(started - submittedNanos);
            try {
                delegate.run();
            } finally {
                metrics.taskCompleted(System.nanoTime() - submittedNanos);
            }
        }
    }

    /** 支持连续写批聚合的指标跟踪包装器：完整保留每条任务的排队与完成指标。 */
    private static final class TrackedBatchTask implements BatchableShardTask {
        private final BatchableShardTask delegate;
        private final long submittedNanos;
        private final ConcurrencyMetrics metrics;

        TrackedBatchTask(BatchableShardTask delegate, long submittedNanos, ConcurrencyMetrics metrics) {
            this.delegate = delegate;
            this.submittedNanos = submittedNanos;
            this.metrics = metrics;
        }

        @Override
        public Object batchKey() {
            return delegate.batchKey();
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            metrics.taskStarted(started - submittedNanos);
            try {
                delegate.run();
            } finally {
                metrics.taskCompleted(System.nanoTime() - submittedNanos);
            }
        }

        @Override
        public void runBatch(int shardId, List<BatchableShardTask> tasks) {
            long started = System.nanoTime();
            int n = tasks.size();
            long[] submittedTimes = TL_SUBMIT_TIMES.get();
            if (submittedTimes.length < n) {
                submittedTimes = new long[n];
                TL_SUBMIT_TIMES.set(submittedTimes);
            }
            for (int i = 0; i < n; i++) {
                TrackedBatchTask t = (TrackedBatchTask) tasks.get(i);
                submittedTimes[i] = t.submittedNanos;
                metrics.taskStarted(started - t.submittedNanos);
                tasks.set(i, t.delegate);
            }
            try {
                delegate.runBatch(shardId, tasks);
            } finally {
                long finished = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    metrics.taskCompleted(finished - submittedTimes[i]);
                }
            }
        }

        private static final ThreadLocal<long[]> TL_SUBMIT_TIMES =
                ThreadLocal.withInitial(() -> new long[64]);
    }
}
