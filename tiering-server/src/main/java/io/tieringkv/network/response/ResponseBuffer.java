package io.tieringkv.network.response;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.tieringkv.protocol.RespEncoder;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.protocol.RespValue;

/**
 * 每连接复用响应缓冲（ADR-0033）：编码累积于单个 ByteBuf，
 * 批处理一次写出，降低每响应分配。
 */
public final class ResponseBuffer {

    private final ByteBufAllocator allocator;
    private ByteBuf buffer;

    public ResponseBuffer(ByteBufAllocator allocator) {
        this.allocator = allocator;
    }

    public void append(RespValue value) {
        append(value, RespVersion.RESP2);
    }

    public void append(RespValue value, RespVersion version) {
        if (buffer == null) {
            buffer = allocator.buffer(256);
        }
        RespEncoder.write(buffer, value, version);
    }

    public boolean isEmpty() {
        return buffer == null || buffer.readableBytes() == 0;
    }

    /** 取出缓冲（所有权移交写出方），下次 append 重新分配。 */
    public ByteBuf takeAndReset() {
        ByteBuf out = buffer;
        buffer = null;
        return out;
    }

    public void releaseIfEmpty() {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }
}
