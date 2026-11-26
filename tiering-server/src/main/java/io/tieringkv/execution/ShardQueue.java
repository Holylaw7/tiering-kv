package io.tieringkv.execution;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 分片任务队列（ADR-0023 / B1 优化）：FIFO，支持单次锁批拉取 {@link #drainTo} 与深度观测。 */
public final class ShardQueue {

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    public boolean offer(Runnable task) {
        return queue.offer(task);
    }

    public Runnable poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 批量排空最多 maxElements 个任务，减少高并发下锁获取与线程唤醒次数。 */
    public int drainTo(Collection<? super Runnable> target, int maxElements) {
        return queue.drainTo(target, maxElements);
    }

    public int size() {
        return queue.size();
    }
}
