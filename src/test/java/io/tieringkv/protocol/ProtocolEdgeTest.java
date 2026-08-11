package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 协议边缘矩阵（ADR-0103）：整数、嵌套、空值、错误类型。 */
class ProtocolEdgeTest {

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, -1, Long.MAX_VALUE, Long.MIN_VALUE})
    void integerBoundaries(long value) {
        RespInteger integer = new RespInteger(value);
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, integer);
        assertThat(buffer.toString(StandardCharsets.US_ASCII))
                .startsWith(":" + value + "\r\n");
        buffer.release();
    }

    @Test
    void nestedArrayRoundTrip() {
        RespArray nested = new RespArray(List.of(
                new RespArray(List.of(new RespBulkString(
                        "a".getBytes()))),
                new RespBulkString("b".getBytes())));
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, nested);
        RespValue decoded = decodeOnce(buffer);
        assertThat(decoded).isInstanceOf(RespArray.class);
        assertThat(((RespArray) decoded).values()).hasSize(2);
        buffer.release();
    }

    @Test
    void inlinePingVariant() {
        ByteBuf buffer = Unpooled.copiedBuffer(
                "ping\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(decodeAll(buffer)).hasSize(1);
        buffer.release();
    }

    @Test
    void inlineEchoVariant() {
        ByteBuf buffer = Unpooled.copiedBuffer(
                "ECHO hi\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(decodeAll(buffer)).hasSize(1);
        buffer.release();
    }

    @Test
    void nullBulkStringDecoded() {
        ByteBuf buffer = Unpooled.copiedBuffer(
                "$-1\r\n".getBytes(StandardCharsets.US_ASCII));
        RespValue decoded = decodeOnce(buffer);
        assertThat(decoded).isEqualTo(RespNull.BULK_STRING);
        buffer.release();
    }

    @Test
    void errorTypesRoundTrip() {
        for (RespError error : List.of(
                RespError.wrongArity("GET"),
                RespError.unknownCommand("FOO"),
                RespError.protocol("bad frame"))) {
            ByteBuf buffer = Unpooled.buffer();
            RespEncoder.write(buffer, error);
            RespValue decoded = decodeOnce(buffer);
            assertThat(decoded).isInstanceOf(RespError.class);
            buffer.release();
        }
    }

    @Test
    void metaCommandNullFieldsRoundTrip() {
        io.tieringkv.transaction.metadata.TxnMetaCommand command =
                new io.tieringkv.transaction.metadata.TxnMetaCommand(
                        io.tieringkv.transaction.metadata.TxnMetaCommand.Type
                                .REGISTER,
                        "t1", null, 1, 0, -1, null, -1,
                        java.util.Map.of());
        io.tieringkv.transaction.metadata.TxnMetaCommand decoded =
                io.tieringkv.transaction.metadata.TxnMetaCodec.decode(
                        io.tieringkv.transaction.metadata.TxnMetaCodec
                                .encode(command));
        assertThat(decoded.primary()).isEmpty();
        assertThat(decoded.regionMutations()).isEmpty();
    }

    private static RespValue decodeOnce(ByteBuf buffer) {
        return decodeAll(buffer).get(0);
    }

    private static List<RespValue> decodeAll(ByteBuf buffer) {
        io.netty.channel.embedded.EmbeddedChannel channel =
                new io.netty.channel.embedded.EmbeddedChannel(
                        new RespDecoder());
        try {
            channel.writeInbound(buffer.copy());
            java.util.List<RespValue> values = new java.util.ArrayList<>();
            Object item;
            while ((item = channel.readInbound()) != null) {
                values.add((RespValue) item);
            }
            return values;
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
