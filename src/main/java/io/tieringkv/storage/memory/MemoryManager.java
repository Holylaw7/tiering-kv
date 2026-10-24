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

    private final long maxBytes;
    private final long heapLimitBytes;
    private static final long GC_INTERVAL_NANOS = 1_000_000_000L;

    private final AtomicLong usedBytes = new AtomicLong();
    private final AtomicLong lastGcNanos = new AtomicLong(0);
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
     * <p>堆判定前会先尝试一次全量 GC（限频 1 秒）：`totalMemory - freeMemory`
     * 包含尚未回收的垃圾，突发写入后即使数据已删除，指标也会长期偏高，
     * 若直接据此拒绝写会造成"永远无法恢复"的自锁。GC 后仍超阈值才判定超限。
     */
    public boolean isOverLimit() {
        if (usedBytes.get() > maxBytes) {
            return true;
        }
        if (heapLimitBytes <= 0) {
            return false;
        }
        if (heapUsedBytes() <= heapLimitBytes) {
            return false;
        }
        long now = System.nanoTime();
        long last = lastGcNanos.get();
        if (now - last > GC_INTERVAL_NANOS && lastGcNanos.compareAndSet(last, now)) {
            System.gc();
        }
        return heapUsedBytes() > heapLimitBytes;
    }
}
