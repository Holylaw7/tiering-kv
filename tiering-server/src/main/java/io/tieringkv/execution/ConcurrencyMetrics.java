package io.tieringkv.execution;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 并发指标（ADR-0023 / B1 优化）：队列深度、分片利用率、等待与操作延迟。
 *
 * <p>{@code activeTasks} 改为分段条带 {@link LongAdder}，{@code maxQueueDepth} 采用
 * 快路径无锁读预判，彻底消除多核分片线程在单一缓存行上的 CAS 争用。
 */
public final class ConcurrencyMetrics {

    private final LongAdder operations = new LongAdder();
    private final LongAdder waitNanos = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong maxQueueDepth = new AtomicLong();
    private final LongAdder activeTasks = new LongAdder();

    public void taskSubmitted(int depth) {
        operations.increment();
        queueDepth.setRelease(depth);
        if (depth > maxQueueDepth.getOpaque()) {
            maxQueueDepth.accumulateAndGet(depth, Math::max);
        }
    }

    public void taskStarted(long waitNanos) {
        this.waitNanos.add(waitNanos);
        activeTasks.increment();
    }

    public void taskCompleted(long totalNanos) {
        latencyNanos.add(totalNanos);
        activeTasks.decrement();
    }

    public Snapshot snapshot() {
        long ops = operations.sum();
        return new Snapshot(
                ops,
                ops == 0 ? 0 : waitNanos.sum() / ops,
                ops == 0 ? 0 : latencyNanos.sum() / ops,
                queueDepth.get(),
                maxQueueDepth.get(),
                Math.max(0L, activeTasks.sum()));
    }

    public record Snapshot(
            long operations,
            long avgWaitNanos,
            long avgLatencyNanos,
            long queueDepth,
            long maxQueueDepth,
            long activeTasks) {
    }
}
