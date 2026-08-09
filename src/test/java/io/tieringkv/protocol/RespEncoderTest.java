package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RespEncoderTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder());

    @Test
    void encodesSimpleString() {
        assertWire(new RespSimpleString("OK"), "+OK\r\n");
    }

    @Test
    void encodesError() {
        assertWire(new RespError("ERR boom"), "-ERR boom\r\n");
    }

    @Test
    void encodesInteger() {
        assertWire(new RespInteger(42), ":42\r\n");
    }

    @Test
    void encodesBulkString() {
        assertWire(new RespBulkString("hello".getBytes(StandardCharsets.UTF_8)), "$5\r\nhello\r\n");
    }

    @Test
    void encodesNullBulk() {
        assertWire(RespNull.BULK_STRING, "$-1\r\n");
    }

    @Test
    void encodesNullArray() {
        assertWire(RespNull.ARRAY, "*-1\r\n");
    }

    @Test
    void encodesArray() {
        assertWire(new RespArray(List.of(
                new RespBulkString("foo".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("bar".getBytes(StandardCharsets.UTF_8)))),
                "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
    }

    @Test
    void sanitizesCrLfInErrorToPreventInjection() {
        assertWire(new RespError("ERR bad\r\nmsg"), "-ERR bad  msg\r\n");
    }

    private void assertWire(RespValue value, String expected) {
        channel.writeOutbound(value);
        ByteBuf out = channel.readOutbound();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(expected);
        out.release();
    }
}
