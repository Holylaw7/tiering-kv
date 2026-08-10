package io.tieringkv.execution;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 并发指标（ADR-0023）：队列深度、分片利用率、等待与操作延迟。 */
public final class ConcurrencyMetrics {

    private final LongAdder operations = new LongAdder();
    private final LongAdder waitNanos = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong maxQueueDepth = new AtomicLong();
    private final AtomicLong activeTasks = new AtomicLong();

    public void taskSubmitted(int depth) {
        operations.increment();
        queueDepth.set(depth);
        maxQueueDepth.accumulateAndGet(depth, Math::max);
    }

    public void taskStarted(long waitNanos) {
        this.waitNanos.add(waitNanos);
        activeTasks.incrementAndGet();
    }

    public void taskCompleted(long totalNanos) {
        latencyNanos.add(totalNanos);
        activeTasks.decrementAndGet();
    }

    public Snapshot snapshot() {
        long ops = operations.sum();
        return new Snapshot(
                ops,
                ops == 0 ? 0 : waitNanos.sum() / ops,
                ops == 0 ? 0 : latencyNanos.sum() / ops,
                queueDepth.get(),
                maxQueueDepth.get(),
                activeTasks.get());
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
