package io.tieringkv.storage.tiering;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台工作线程池（ADR-0020）：daemon 线程，worker 异常被包装捕获，
 * 不会导致 server 退出。
 */
public final class TierWorkerPool implements AutoCloseable {

    private final ThreadPoolExecutor executor;

    public TierWorkerPool(int workers, String poolName) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, poolName + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        this.executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory);
    }

    /** 提交任务；异常被包装捕获（不传播到调用方线程之外的线程）。 */
    public void execute(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 隔离 worker 异常：记录并继续，不导致 server 退出
                }
            });
        } catch (RejectedExecutionException e) {
            // 池已关闭：静默丢弃（控制器关闭阶段）
        }
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public long queueSize() {
        return executor.getQueue().size();
    }

    /** 测试/关闭辅助：等待队列与活动任务清空。 */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while ((executor.getActiveCount() > 0 || executor.getQueue().size() > 0)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
