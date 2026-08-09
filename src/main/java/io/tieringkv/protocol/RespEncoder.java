package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * RESP2 编码器（ADR-0006）。
 * 错误/简单字符串中的 CR/LF 替换为空格，防止响应注入。
 */
public final class RespEncoder extends MessageToByteEncoder<RespValue> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RespValue msg, ByteBuf out) {
        write(out, msg);
    }

    /** 将值写入缓冲区（纯函数式，供协议错误等场景直接使用）。 */
    public static void write(ByteBuf out, RespValue value) {
        // RespValue 为 sealed 类型（ADR-0006），此链覆盖全部子类型
        if (value instanceof RespSimpleString simple) {
            writePrefixedLine(out, '+', sanitize(simple.value()));
        } else if (value instanceof RespError error) {
            writePrefixedLine(out, '-', sanitize(error.message()));
        } else if (value instanceof RespInteger integer) {
            out.writeCharSequence(":" + integer.value() + "\r\n", StandardCharsets.US_ASCII);
        } else if (value instanceof RespBulkString bulk) {
            writeBulkString(out, bulk.bytes());
        } else if (value instanceof RespNull nul) {
            if (nul == RespNull.BULK_STRING) {
                out.writeCharSequence("$-1\r\n", StandardCharsets.US_ASCII);
            } else {
                out.writeCharSequence("*-1\r\n", StandardCharsets.US_ASCII);
            }
        } else if (value instanceof RespArray array) {
            out.writeByte('*');
            out.writeCharSequence(array.values().size() + "\r\n", StandardCharsets.US_ASCII);
            for (RespValue element : array.values()) {
                write(out, element);
            }
        }
    }

    private static void writePrefixedLine(ByteBuf out, char prefix, String text) {
        out.writeByte(prefix);
        out.writeCharSequence(text, StandardCharsets.UTF_8);
        out.writeCharSequence("\r\n", StandardCharsets.US_ASCII);
    }

    private static void writeBulkString(ByteBuf out, byte[] bytes) {
        out.writeByte('$');
        out.writeCharSequence(bytes.length + "\r\n", StandardCharsets.US_ASCII);
        out.writeBytes(bytes);
        out.writeCharSequence("\r\n", StandardCharsets.US_ASCII);
    }

    private static String sanitize(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
