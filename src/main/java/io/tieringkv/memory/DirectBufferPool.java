package io.tieringkv.memory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * DirectByteBuffer 大小类池（ADR-0027）：4K/16K/64K/256K 槽位，
 * allocate 复用或新分配，release 归还（池满则交给 GC）。
 */
public final class DirectBufferPool {

    public static final int[] SIZE_CLASSES = {4096, 16_384, 65_536, 262_144};
    private static final int MAX_PER_BUCKET = 64;

    private final AllocationTracker tracker;
    private final Map<Integer, ConcurrentLinkedDeque<ByteBuffer>> pools = new ConcurrentHashMap<>();

    public DirectBufferPool(AllocationTracker tracker) {
        this.tracker = tracker;
        for (int size : SIZE_CLASSES) {
            pools.put(size, new ConcurrentLinkedDeque<>());
        }
    }

    public ByteBuffer allocate(int size) {
        int bucket = sizeClass(size);
        if (bucket == 0) {
            tracker.recordAllocate(size);
            return ByteBuffer.allocateDirect(size);
        }
        ByteBuffer reused = pools.get(bucket).poll();
        if (reused != null) {
            tracker.recordReuse();
            return reused;
        }
        tracker.recordAllocate(bucket);
        return ByteBuffer.allocateDirect(bucket);
    }

    public void release(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        int bucket = sizeClass(buffer.capacity());
        tracker.recordRelease(buffer.capacity());
        if (bucket == 0) {
            return; // 非标准大小：直接交给 GC
        }
        buffer.clear();
        ConcurrentLinkedDeque<ByteBuffer> deque = pools.get(bucket);
        if (deque.size() < MAX_PER_BUCKET) {
            deque.offer(buffer);
        }
    }

    public AllocationTracker tracker() {
        return tracker;
    }

    /** 返回匹配的大小类容量；超过最大类返回 0（不池化）。 */
    private static int sizeClass(int size) {
        for (int bucket : SIZE_CLASSES) {
            if (size <= bucket) {
                return bucket;
            }
        }
        return 0;
    }
}
