package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RespDecoderTest {

    private final ExceptionCatcher catcher = new ExceptionCatcher();
    private final EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder(), catcher);

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void decodesSimpleString() {
        write("+OK\r\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void decodesError() {
        write("-ERR boom\r\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespError("ERR boom"));
    }

    @Test
    void decodesInteger() {
        write(":42\r\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespInteger(42));
    }

    @Test
    void decodesBulkString() {
        write("$5\r\nhello\r\n");
        assertThat(channel.<RespValue>readInbound())
                .isEqualTo(new RespBulkString("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesNullBulk() {
        write("$-1\r\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void decodesArray() {
        write("*2\r\n$3\r\nSET\r\n$3\r\nkey\r\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespArray(List.of(
                new RespBulkString("SET".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("key".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void decodesNestedArray() {
        write("*1\r\n*1\r\n:1\r\n");
        assertThat(channel.<RespValue>readInbound())
                .isEqualTo(new RespArray(List.of(new RespArray(List.of(new RespInteger(1))))));
    }

    @Test
    void incompleteInputWaitsForMoreBytes() {
        write("+OK\r");
        assertThat(channel.<Object>readInbound()).isNull();
        write("\n");
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void partialBulkAccumulates() {
        write("$5\r\nhel");
        assertThat(channel.<Object>readInbound()).isNull();
        write("lo\r\n");
        assertThat(channel.<RespValue>readInbound())
                .isEqualTo(new RespBulkString("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pipelineDecodesMultipleCommandsInOrder() {
        write("*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n");
        assertThat(channel.<RespValue>readInbound())
                .isEqualTo(new RespArray(List.of(new RespBulkString("PING".getBytes(StandardCharsets.UTF_8)))));
        assertThat(channel.<RespValue>readInbound()).isEqualTo(new RespArray(List.of(
                new RespBulkString("SET".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("k".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("v".getBytes(StandardCharsets.UTF_8)))));
        assertThat(channel.<Object>readInbound()).isNull();
    }

    @Test
    void inlineCommandDecodedAsArray() {
        write("PING\r\n");
        assertThat(channel.<RespValue>readInbound())
                .isEqualTo(new RespArray(List.of(new RespBulkString("PING".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void malformedBulkLengthFails() {
        write("$abc\r\n");
        assertThat(catcher.cause)
                .isInstanceOf(DecoderException.class)
                .hasCauseInstanceOf(RespProtocolException.class);
    }

    @Test
    void missingCrLfAfterBulkFails() {
        write("$2\r\nabXX");
        assertThat(catcher.cause)
                .isInstanceOf(DecoderException.class)
                .hasCauseInstanceOf(RespProtocolException.class);
    }

    private void write(String wire) {
        channel.writeInbound(buffer(wire));
    }

    private static ByteBuf buffer(String wire) {
        return Unpooled.copiedBuffer(wire, StandardCharsets.UTF_8);
    }

    /** 捕获解码异常，避免异常传播中断 EmbeddedChannel。 */
    private static final class ExceptionCatcher extends ChannelInboundHandlerAdapter {

        private Throwable cause;

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            this.cause = cause;
        }
    }
}
