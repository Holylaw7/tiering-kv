package io.tieringkv.storage.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存计量：统计 usedBytes / maxBytes，并持有淘汰回调（锁外触发）。
 *
 * <p>TD-083：只按"键 + 值字节数"计量会严重低估真实占用（SkipList 节点、
 * 对象头、WAL 缓冲、分片队列）。当 JVM 堆先于计量值触顶时会直接
 * OutOfMemoryError，进程看似存活但事件循环已死。因此超限判定同时参考
 * 实际堆占用：{@link #isOverLimit()} = 计量超限 <b>或</b> 堆占用超过
 * 堆阈值（默认 {@code -Xmx} 的 85%，可用系统属性
 * {@code -Dtieringkv.memory.heap-ratio=0.9} 调整）。
 */
public final class MemoryManager {

    private volatile long maxBytes;
    private final long heapLimitBytes;

    private final AtomicLong usedBytes = new AtomicLong();
    private volatile EvictionCallback evictionCallback;

    public MemoryManager(long maxBytes) {
        this(maxBytes, defaultHeapLimit());
    }

    /** 测试/定制用：heapLimitBytes<=0 表示只按计量值判定（旧行为）。 */
    public MemoryManager(long maxBytes, long heapLimitBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.heapLimitBytes = heapLimitBytes;
    }

    private static long defaultHeapLimit() {
        double ratio = 0.9;
        String raw = System.getProperty("tieringkv.memory.heap-ratio");
        if (raw != null && !raw.isBlank()) {
            try {
                ratio = Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                // 非法配置退回默认值
            }
        }
        if (ratio <= 0 || ratio > 1) {
            ratio = 0.9;
        }
        return (long) (Runtime.getRuntime().maxMemory() * ratio);
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

    /** 运行期动态调整内存上限（供 CONFIG SET maxmemory 接线）。 */
    public void setMaxBytes(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** 当前 JVM 堆占用（total - free）。 */
    public long heapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public long heapLimitBytes() {
        return heapLimitBytes;
    }

    /**
     * 计量超限，或实际堆接近上限（TD-083）。
     *
     * <p>不在业务请求热路径同步调用 {@code System.gc()}，避免 STW Full GC
     * 引发长尾抖动；仅基于计量值与当前堆占用判定超限。
     */
    public boolean isOverLimit() {
        if (usedBytes.get() > maxBytes) {
            return true;
        }
        if (heapLimitBytes <= 0) {
            return false;
        }
        return heapUsedBytes() > heapLimitBytes;
    }
}
