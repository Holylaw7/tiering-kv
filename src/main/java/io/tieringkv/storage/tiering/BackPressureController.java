package io.tieringkv.storage.tiering;

import io.tieringkv.storage.memory.MemoryManager;

import java.util.function.LongSupplier;

/** 背压控制（ADR-0021）：CRITICAL 时有界等待，超时由写路径拒绝。 */
public final class BackPressureController {

    private final WatermarkManager watermark;
    private final MemoryManager memory;
    private final LongSupplier entryCount;
    private final LongSupplier pendingTasks;
    private final Object monitor = new Object();

    public BackPressureController(
            WatermarkManager watermark,
            MemoryManager memory,
            LongSupplier entryCount,
            LongSupplier pendingTasks) {
        this.watermark = watermark;
        this.memory = memory;
        this.entryCount = entryCount;
        this.pendingTasks = pendingTasks;
    }

    public TierState currentState() {
        return watermark.evaluate(
                memory.usedBytes(), memory.maxBytes(), entryCount.getAsLong(), pendingTasks.getAsLong());
    }

    /** 等待可写；超时返回 false（调用方抛 BackpressureException）。 */
    public boolean awaitWritable(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (currentState() == TierState.CRITICAL) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            synchronized (monitor) {
                try {
                    monitor.wait(Math.min(50, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    public void notifyStateChanged() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}
