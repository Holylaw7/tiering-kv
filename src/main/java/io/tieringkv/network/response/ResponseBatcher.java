package io.tieringkv.network.response;

import io.netty.buffer.ByteBuf;
import io.tieringkv.protocol.RespValue;

import java.util.function.Consumer;

/**
 * 自适应响应批处理（ADR-0032）：批满阈值或本批请求归零时一次 flush，
 * 顺序由 ResponseSequencer 保证；仅由连接事件循环访问。
 */
public final class ResponseBatcher {

    private final ResponseBuffer buffer;
    private final int batchThreshold;
    private final Consumer<ByteBuf> flusher;
    private int pending;

    public ResponseBatcher(ResponseBuffer buffer, int batchThreshold, Consumer<ByteBuf> flusher) {
        this.buffer = buffer;
        this.batchThreshold = batchThreshold;
        this.flusher = flusher;
    }

    public void offer(RespValue value, boolean noMorePending) {
        buffer.append(value);
        pending++;
        if (pending >= batchThreshold || noMorePending) {
            flush();
        }
    }

    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        ByteBuf out = buffer.takeAndReset();
        pending = 0;
        flusher.accept(out);
    }

    public void close() {
        buffer.releaseIfEmpty();
    }
}
