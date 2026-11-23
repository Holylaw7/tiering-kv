package io.tieringkv.memory;

import java.nio.ByteBuffer;

/** 分配/释放门面（ADR-0027）：按需借用池化 DirectByteBuffer。 */
public final class BufferArena {

    private final DirectBufferPool pool;

    public BufferArena(DirectBufferPool pool) {
        this.pool = pool;
    }

    public ByteBuffer allocate(int size) {
        return pool.allocate(size);
    }

    public void release(ByteBuffer buffer) {
        pool.release(buffer);
    }
}
