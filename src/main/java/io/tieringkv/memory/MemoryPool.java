package io.tieringkv.memory;

/** 内存池入口（ADR-0027）：allocate/release/reuse + 统计。 */
public final class MemoryPool implements AutoCloseable {

    private final DirectBufferPool pool;

    public MemoryPool() {
        this.pool = new DirectBufferPool(new AllocationTracker());
    }

    public MemoryPool(AllocationTracker tracker) {
        this.pool = new DirectBufferPool(tracker);
    }

    public BufferRecycler allocate(int size) {
        return new BufferRecycler(pool, pool.allocate(size));
    }

    /** 原始分配（调用方自行 release；供 BlockCache 等长期持有场景）。 */
    public java.nio.ByteBuffer allocateRaw(int size) {
        return pool.allocate(size);
    }

    public void release(java.nio.ByteBuffer buffer) {
        pool.release(buffer);
    }

    public AllocationTracker tracker() {
        return pool.tracker();
    }

    @Override
    public void close() {
        // 池内引用清空后由 GC/Cleaner 回收 native 内存
    }
}
