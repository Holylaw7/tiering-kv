package io.tieringkv.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分片 worker（ADR-0023 / B1 优化）：单线程批拉取消费分片队列；异常隔离。
 *
 * <p>使用分片本地 {@code completed} 单写者计数器替代跨分片全局 {@code AtomicInteger}，
 * 消除 16 核缓存行乒乓，并通过 {@code drainTo} 一次唤醒连续消费同分片一批任务。
 */
final class ShardWorker implements Runnable {

    private static final int MAX_DRAIN_BATCH = 64;

    private final ShardQueue queue;
    private final AtomicBoolean closed;
    private final AtomicLong completed = new AtomicLong();
    private long localCompleted;

    ShardWorker(ShardQueue queue, AtomicBoolean closed) {
        this.queue = queue;
        this.closed = closed;
    }

    long completedCount() {
        return completed.getAcquire();
    }

    @Override
    public void run() {
        List<Runnable> batch = new ArrayList<>(MAX_DRAIN_BATCH);
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
            runIsolated(first);
            int extra = queue.drainTo(batch, MAX_DRAIN_BATCH - 1);
            if (extra > 0) {
                for (int i = 0; i < extra; i++) {
                    runIsolated(batch.get(i));
                }
                batch.clear();
            }
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
