package io.tieringkv.execution;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Key 分片执行器（ADR-0023 / B1 优化）：{@code hash(key) % N} 路由，每分片单 worker——
 * 同键 FIFO 有序、异键并行；每分片独立 {@link LongAdder} 提交计数 + 单写者完成计数，
 * 零全局原子争用且保证 {@link #awaitIdle} 无竞态窗口。
 */
public final class KeyShardExecutor implements AutoCloseable {

    private final int shardCount;
    private final ShardQueue[] queues;
    private final ShardWorker[] workers;
    private final LongAdder[] submittedPerShard;
    private final Thread[] threads;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrencyMetrics metrics = new ConcurrencyMetrics();

    public KeyShardExecutor(int shardCount, String name) {
        this.shardCount = Math.max(1, shardCount);
        this.queues = new ShardQueue[this.shardCount];
        this.workers = new ShardWorker[this.shardCount];
        this.submittedPerShard = new LongAdder[this.shardCount];
        this.threads = new Thread[this.shardCount];
        for (int i = 0; i < this.shardCount; i++) {
            queues[i] = new ShardQueue();
            workers[i] = new ShardWorker(queues[i], closed);
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
        boolean offered = queues[shard].offer(new TrackedTask(task, submitted, metrics));
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
}
