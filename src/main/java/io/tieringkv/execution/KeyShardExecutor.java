package io.tieringkv.execution;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Key 分片执行器（ADR-0023）：`hash(key) % N` 路由，每分片单 worker——
 * 同键 FIFO 有序、异键并行；daemon 线程，异常隔离。
 */
public final class KeyShardExecutor implements AutoCloseable {

    private final int shardCount;
    private final ShardQueue[] queues;
    private final Thread[] threads;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private final ConcurrencyMetrics metrics = new ConcurrencyMetrics();

    public KeyShardExecutor(int shardCount, String name) {
        this.shardCount = Math.max(1, shardCount);
        this.queues = new ShardQueue[this.shardCount];
        this.threads = new Thread[this.shardCount];
        for (int i = 0; i < this.shardCount; i++) {
            queues[i] = new ShardQueue();
            Thread thread = new Thread(
                    new ShardWorker(queues[i], metrics, closed, active),
                    name + "-" + i);
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
        boolean offered = queues[shard].offer(() -> {
            metrics.taskStarted(System.nanoTime() - submitted);
            try {
                task.run();
            } finally {
                metrics.taskCompleted(System.nanoTime() - submitted);
            }
        });
        metrics.taskSubmitted(queues[shard].size());
        return offered;
    }

    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean idle = active.get() == 0;
            for (ShardQueue queue : queues) {
                if (queue.size() > 0) {
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
}
