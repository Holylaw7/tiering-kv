package io.tieringkv.storage.memory;

import java.util.concurrent.atomic.AtomicLong;

/** 内存计量：统计 usedBytes / maxBytes，并持有淘汰回调（锁外触发）。 */
public final class MemoryManager {

    private final long maxBytes;
    private final AtomicLong usedBytes = new AtomicLong();
    private volatile EvictionCallback evictionCallback;

    public MemoryManager(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public void setEvictionCallback(EvictionCallback callback) {
        this.evictionCallback = callback;
    }

    public EvictionCallback evictionCallback() {
        return evictionCallback;
    }

    public void add(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative delta");
        }
        usedBytes.addAndGet(bytes);
    }

    public void remove(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative delta");
        }
        usedBytes.updateAndGet(current -> Math.max(0, current - bytes));
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public long maxBytes() {
        return maxBytes;
    }

    public boolean isOverLimit() {
        return usedBytes.get() > maxBytes;
    }
}
