package io.tieringkv.network.response;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.tieringkv.protocol.RespSimpleString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBatcherTest {

    @Test
    void flushesWhenBatchThresholdReached() {
        List<ByteBuf> flushed = new ArrayList<>();
        ResponseBatcher batcher = new ResponseBatcher(
                new ResponseBuffer(UnpooledByteBufAllocator.DEFAULT), 3,
                flushed::add);
        batcher.offer(new RespSimpleString("A"), false);
        batcher.offer(new RespSimpleString("B"), false);
        assertThat(flushed).isEmpty();
        batcher.offer(new RespSimpleString("C"), false);
        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).toString(StandardCharsets.UTF_8))
                .isEqualTo("+A\r\n+B\r\n+C\r\n");
        flushed.get(0).release();
        batcher.close();
    }

    @Test
    void flushesWhenNoMorePending() {
        List<ByteBuf> flushed = new ArrayList<>();
        ResponseBatcher batcher = new ResponseBatcher(
                new ResponseBuffer(UnpooledByteBufAllocator.DEFAULT), 64,
                flushed::add);
        batcher.offer(new RespSimpleString("OK"), true);
        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).toString(StandardCharsets.UTF_8)).isEqualTo("+OK\r\n");
        flushed.get(0).release();
        batcher.close();
    }

    @Test
    void closeReleasesPendingBuffer() {
        ResponseBatcher batcher = new ResponseBatcher(
                new ResponseBuffer(UnpooledByteBufAllocator.DEFAULT), 64,
                buf -> buf.release());
        batcher.offer(new RespSimpleString("OK"), false);
        batcher.close();
        batcher.close(); // 幂等
    }
}
