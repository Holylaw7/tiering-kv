package io.tieringkv.memory;

import java.nio.ByteBuffer;

/** 借用/归还包装（ADR-0027）：close 即回池，防止泄漏。 */
public final class BufferRecycler implements AutoCloseable {

    private final DirectBufferPool pool;
    private ByteBuffer buffer;

    BufferRecycler(DirectBufferPool pool, ByteBuffer buffer) {
        this.pool = pool;
        this.buffer = buffer;
    }

    public ByteBuffer get() {
        return buffer;
    }

    @Override
    public void close() {
        if (buffer != null) {
            pool.release(buffer);
            buffer = null;
        }
    }
}
