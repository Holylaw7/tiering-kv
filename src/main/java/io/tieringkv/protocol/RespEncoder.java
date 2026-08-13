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
        } else if (value instanceof RespMap map) {
            // RESP2 回退：Map 编码为平铺数组
            write(out, new RespArray(map.pairs()));
        } else if (value instanceof RespSet set) {
            write(out, new RespArray(set.values()));
        } else if (value instanceof RespDouble number) {
            out.writeCharSequence(":" + number.value() + "\r\n",
                    StandardCharsets.US_ASCII);
        } else if (value instanceof RespBigNumber big) {
            write(out, new RespBulkString(
                    big.value().getBytes(StandardCharsets.UTF_8)));
        } else if (value instanceof RespPush push) {
            write(out, new RespArray(push.values()));
        }
    }

    /** RESP3 编码：新类型原生表达；旧类型与 RESP2 一致。 */
    public static void writeV3(ByteBuf out, RespValue value) {
        if (value instanceof RespMap map) {
            out.writeByte('%');
            out.writeCharSequence(map.pairs().size() / 2 + "\r\n",
                    StandardCharsets.US_ASCII);
            for (RespValue element : map.pairs()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespSet set) {
            out.writeByte('~');
            out.writeCharSequence(set.values().size() + "\r\n",
                    StandardCharsets.US_ASCII);
            for (RespValue element : set.values()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespDouble number) {
            out.writeByte(',');
            out.writeCharSequence(number.value() + "\r\n",
                    StandardCharsets.US_ASCII);
        } else if (value instanceof RespBigNumber big) {
            out.writeByte('(');
            out.writeCharSequence(big.value() + "\r\n",
                    StandardCharsets.US_ASCII);
        } else if (value instanceof RespPush push) {
            out.writeByte('>');
            out.writeCharSequence(push.values().size() + 1
                    + "\r\n", StandardCharsets.US_ASCII);
            writeBulkString(out, push.type().getBytes(
                    StandardCharsets.UTF_8));
            for (RespValue element : push.values()) {
                writeV3(out, element);
            }
        } else {
            write(out, value);
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
