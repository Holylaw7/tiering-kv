package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 零分配 RESP2/RESP3 编解码边界与拆包回归测试。
 */
class RespZeroAllocCodecTest {

    @Test
    void decodesBoundaryIntegersAndRejectsOverflow() {
        assertThat(decode(":0\r\n")).isEqualTo(new RespInteger(0L));
        assertThat(decode(":-1\r\n")).isEqualTo(new RespInteger(-1L));
        assertThat(decode(":+42\r\n")).isEqualTo(new RespInteger(42L));
        assertThat(decode(":" + Long.MAX_VALUE + "\r\n")).isEqualTo(new RespInteger(Long.MAX_VALUE));
        assertThat(decode(":" + Long.MIN_VALUE + "\r\n")).isEqualTo(new RespInteger(Long.MIN_VALUE));

        assertThatThrownBy(() -> decode(":9223372036854775808\r\n"))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> decode(":-9223372036854775809\r\n"))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> decode(":-\r\n"))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> decode(": \r\n"))
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void decodesByteByByteFragmentedPipelineCleanly() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());
        try {
            byte[] wire = "*3\r\n$3\r\nSET\r\n$4\r\nkey1\r\n$6\r\nvalue1\r\n*2\r\n$3\r\nGET\r\n$4\r\nkey1\r\n"
                    .getBytes(StandardCharsets.UTF_8);
            for (byte b : wire) {
                channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{b}));
            }
            RespValue first = channel.readInbound();
            RespValue second = channel.readInbound();
            assertThat(first).isEqualTo(new RespArray(List.of(
                    new RespBulkString("SET".getBytes(StandardCharsets.UTF_8)),
                    new RespBulkString("key1".getBytes(StandardCharsets.UTF_8)),
                    new RespBulkString("value1".getBytes(StandardCharsets.UTF_8)))));
            assertThat(second).isEqualTo(new RespArray(List.of(
                    new RespBulkString("GET".getBytes(StandardCharsets.UTF_8)),
                    new RespBulkString("key1".getBytes(StandardCharsets.UTF_8)))));
            assertThat(channel.<Object>readInbound()).isNull();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void encodesFastPathConstantsSmallAndLargeValuesAndResp3() {
        assertEncoded(new RespSimpleString("OK"), RespVersion.RESP2, "+OK\r\n");
        assertEncoded(new RespSimpleString("PONG"), RespVersion.RESP2, "+PONG\r\n");
        assertEncoded(new RespSimpleString("QUEUED"), RespVersion.RESP2, "+QUEUED\r\n");
        assertEncoded(new RespSimpleString("CUSTOM\r\nLINE"), RespVersion.RESP2, "+CUSTOM  LINE\r\n");
        assertEncoded(RespNull.BULK_STRING, RespVersion.RESP2, "$-1\r\n");
        assertEncoded(RespNull.ARRAY, RespVersion.RESP2, "*-1\r\n");
        assertEncoded(RespNull.BULK_STRING, RespVersion.RESP3, "_\r\n");

        assertEncoded(new RespInteger(0), RespVersion.RESP2, ":0\r\n");
        assertEncoded(new RespInteger(1024), RespVersion.RESP2, ":1024\r\n");
        assertEncoded(new RespInteger(1025), RespVersion.RESP2, ":1025\r\n");
        assertEncoded(new RespInteger(-42), RespVersion.RESP2, ":-42\r\n");
        assertEncoded(new RespInteger(Long.MAX_VALUE), RespVersion.RESP2, ":" + Long.MAX_VALUE + "\r\n");
        assertEncoded(new RespInteger(Long.MIN_VALUE), RespVersion.RESP2, ":" + Long.MIN_VALUE + "\r\n");

        byte[] largePayload = "x".repeat(2048).getBytes(StandardCharsets.US_ASCII);
        assertEncoded(new RespBulkString(largePayload), RespVersion.RESP2, "$2048\r\n" + "x".repeat(2048) + "\r\n");

        RespMap map = new RespMap(List.of(
                new RespBulkString("k".getBytes(StandardCharsets.UTF_8)),
                new RespInteger(7)));
        assertEncoded(map, RespVersion.RESP2, "*2\r\n$1\r\nk\r\n:7\r\n");
        assertEncoded(map, RespVersion.RESP3, "%1\r\n$1\r\nk\r\n:7\r\n");
    }

    @Test
    void rejectsMalformedBulkLengthOverChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());
        try {
            assertThatThrownBy(() -> channel.writeInbound(
                    Unpooled.copiedBuffer("$-2\r\n", StandardCharsets.US_ASCII)))
                    .isInstanceOf(DecoderException.class)
                    .hasCauseInstanceOf(RespProtocolException.class);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static RespValue decode(String wire) {
        ByteBuf buf = Unpooled.copiedBuffer(wire, StandardCharsets.UTF_8);
        try {
            return RespDecoder.decodeSingle(buf);
        } finally {
            buf.release();
        }
    }

    private static void assertEncoded(RespValue value, RespVersion version, String expected) {
        ByteBuf buf = Unpooled.buffer();
        try {
            RespEncoder.write(buf, value, version);
            assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo(expected);
        } finally {
            buf.release();
        }
    }
}
