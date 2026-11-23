package io.tieringkv.execution;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 分片 worker（ADR-0023）：单线程消费分片队列；异常隔离。 */
final class ShardWorker implements Runnable {

    private final ShardQueue queue;
    private final ConcurrencyMetrics metrics;
    private final AtomicBoolean closed;
    private final AtomicInteger active;

    ShardWorker(ShardQueue queue, ConcurrencyMetrics metrics, AtomicBoolean closed, AtomicInteger active) {
        this.queue = queue;
        this.metrics = metrics;
        this.closed = closed;
        this.active = active;
    }

    @Override
    public void run() {
        while (!closed.get()) {
            Runnable task;
            try {
                task = queue.poll(200);
            } catch (InterruptedException e) {
                if (closed.get()) {
                    return;
                }
                continue;
            }
            if (task == null) {
                continue;
            }
            active.incrementAndGet();
            try {
                task.run();
            } catch (Throwable ignored) {
                // 分片 worker 异常隔离：不导致 server 退出
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
