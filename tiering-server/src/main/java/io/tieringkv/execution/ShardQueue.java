package io.tieringkv.execution;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 分片任务队列（ADR-0023）：FIFO，支持深度观测。 */
public final class ShardQueue {

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    public boolean offer(Runnable task) {
        return queue.offer(task);
    }

    public Runnable poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public int size() {
        return queue.size();
    }
}
