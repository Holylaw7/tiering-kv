package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * RESP2 / RESP3 零分配编码器（ADR-0006 / B1 优化）。
 *
 * <p>高频简单响应（+OK / +PONG / $-1 / *-1 / _ / 0..1024 整数）直接写入预编码字节表，
 * 任意长整型与 Bulk/Array 长度直接以 ASCII 十进制写入 {@link ByteBuf}，消除热路径
 * String 拼接分配；错误/简单字符串中的 CR/LF 替换为空格，防止响应注入。
 */
public final class RespEncoder extends MessageToByteEncoder<RespValue> {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] OK_BYTES = "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG_BYTES = "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUEUED_BYTES = "+QUEUED\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_BULK_BYTES = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_ARRAY_BYTES = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_RESP3_BYTES = "_\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MIN_LONG_BYTES = Long.toString(Long.MIN_VALUE).getBytes(StandardCharsets.US_ASCII);

    private static final int SMALL_INT_CACHE_MAX = 1024;
    private static final byte[][] SMALL_INT_RESPONSES = new byte[SMALL_INT_CACHE_MAX + 1][];
    private static final byte[][] SMALL_BULK_HEADERS = new byte[SMALL_INT_CACHE_MAX + 1][];

    static {
        for (int i = 0; i <= SMALL_INT_CACHE_MAX; i++) {
            SMALL_INT_RESPONSES[i] = (":" + i + "\r\n").getBytes(StandardCharsets.US_ASCII);
            SMALL_BULK_HEADERS[i] = ("$" + i + "\r\n").getBytes(StandardCharsets.US_ASCII);
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RespValue msg, ByteBuf out) {
        write(out, msg);
    }

    /** 将值写入缓冲区（纯函数式，供协议错误与响应批处理直接使用）。 */
    public static void write(ByteBuf out, RespValue value) {
        // RespValue 为 sealed 类型（ADR-0006），此链覆盖全部子类型
        if (value instanceof RespSimpleString simple) {
            String text = simple.value();
            if ("OK".equals(text)) {
                out.writeBytes(OK_BYTES);
            } else if ("PONG".equals(text)) {
                out.writeBytes(PONG_BYTES);
            } else if ("QUEUED".equals(text)) {
                out.writeBytes(QUEUED_BYTES);
            } else {
                writePrefixedLine(out, '+', text);
            }
        } else if (value instanceof RespError error) {
            writePrefixedLine(out, '-', error.message());
        } else if (value instanceof RespInteger integer) {
            long val = integer.value();
            if (val >= 0 && val <= SMALL_INT_CACHE_MAX) {
                out.writeBytes(SMALL_INT_RESPONSES[(int) val]);
            } else {
                out.writeByte(':');
                writeAsciiLong(out, val);
                out.writeBytes(CRLF);
            }
        } else if (value instanceof RespBulkString bulk) {
            writeBulkString(out, bulk.bytes());
        } else if (value instanceof RespNull nul) {
            out.writeBytes(nul == RespNull.BULK_STRING ? NULL_BULK_BYTES : NULL_ARRAY_BYTES);
        } else if (value instanceof RespArray array) {
            out.writeByte('*');
            writeAsciiLong(out, array.values().size());
            out.writeBytes(CRLF);
            for (RespValue element : array.values()) {
                write(out, element);
            }
        } else if (value instanceof RespMap map) {
            // RESP2 回退：Map 编码为平铺数组
            out.writeByte('*');
            writeAsciiLong(out, map.pairs().size());
            out.writeBytes(CRLF);
            for (RespValue element : map.pairs()) {
                write(out, element);
            }
        } else if (value instanceof RespSet set) {
            out.writeByte('*');
            writeAsciiLong(out, set.values().size());
            out.writeBytes(CRLF);
            for (RespValue element : set.values()) {
                write(out, element);
            }
        } else if (value instanceof RespDouble number) {
            out.writeByte(':');
            out.writeCharSequence(Double.toString(number.value()), StandardCharsets.US_ASCII);
            out.writeBytes(CRLF);
        } else if (value instanceof RespBigNumber big) {
            writeBulkString(out, big.value().getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof RespPush push) {
            out.writeByte('*');
            writeAsciiLong(out, push.values().size());
            out.writeBytes(CRLF);
            for (RespValue element : push.values()) {
                write(out, element);
            }
        }
    }

    /** 按连接协议版本编码：RESP3 原生表达新类型，RESP2 回退。 */
    public static void write(ByteBuf out, RespValue value,
                             RespVersion version) {
        if (version == RespVersion.RESP3) {
            writeV3(out, value);
        } else {
            write(out, value);
        }
    }

    /** RESP3 编码：新类型原生表达；旧类型与 RESP2 一致。 */
    public static void writeV3(ByteBuf out, RespValue value) {
        if (value instanceof RespMap map) {
            out.writeByte('%');
            writeAsciiLong(out, map.pairs().size() / 2);
            out.writeBytes(CRLF);
            for (RespValue element : map.pairs()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespSet set) {
            out.writeByte('~');
            writeAsciiLong(out, set.values().size());
            out.writeBytes(CRLF);
            for (RespValue element : set.values()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespArray array) {
            // RESP3 数组与 RESP2 头一致，但元素递归使用 RESP3 编码
            // （修复：数组内 RespDouble 曾回退为 ':' 整数风格）
            out.writeByte('*');
            writeAsciiLong(out, array.values().size());
            out.writeBytes(CRLF);
            for (RespValue element : array.values()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespDouble number) {
            out.writeByte(',');
            out.writeCharSequence(Double.toString(number.value()), StandardCharsets.US_ASCII);
            out.writeBytes(CRLF);
        } else if (value instanceof RespBigNumber big) {
            out.writeByte('(');
            out.writeCharSequence(big.value(), StandardCharsets.US_ASCII);
            out.writeBytes(CRLF);
        } else if (value instanceof RespPush push) {
            out.writeByte('>');
            writeAsciiLong(out, push.values().size() + 1);
            out.writeBytes(CRLF);
            writeBulkString(out, push.type().getBytes(StandardCharsets.UTF_8));
            for (RespValue element : push.values()) {
                writeV3(out, element);
            }
        } else if (value instanceof RespNull) {
            // RESP3 null 为 '_'（RESP2 仍为 $-1/*-1，走 write 回退）
            out.writeBytes(NULL_RESP3_BYTES);
        } else {
            write(out, value);
        }
    }

    private static void writePrefixedLine(ByteBuf out, char prefix, String text) {
        out.writeByte(prefix);
        if (hasCrLf(text)) {
            out.writeCharSequence(text.replace('\r', ' ').replace('\n', ' '), StandardCharsets.UTF_8);
        } else {
            out.writeCharSequence(text, StandardCharsets.UTF_8);
        }
        out.writeBytes(CRLF);
    }

    private static boolean hasCrLf(String text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private static void writeBulkString(ByteBuf out, byte[] bytes) {
        int len = bytes.length;
        if (len <= SMALL_INT_CACHE_MAX) {
            out.writeBytes(SMALL_BULK_HEADERS[len]);
        } else {
            out.writeByte('$');
            writeAsciiLong(out, len);
            out.writeBytes(CRLF);
        }
        out.writeBytes(bytes);
        out.writeBytes(CRLF);
    }

    /** 零分配直写十进制 ASCII 有符号 64 位整数。 */
    private static void writeAsciiLong(ByteBuf out, long value) {
        if (value == 0L) {
            out.writeByte('0');
            return;
        }
        if (value == Long.MIN_VALUE) {
            out.writeBytes(MIN_LONG_BYTES);
            return;
        }
        if (value < 0L) {
            out.writeByte('-');
            value = -value;
        }
        int digits = decimalLength(value);
        int base = out.writerIndex();
        out.ensureWritable(digits);
        for (int i = digits - 1; i >= 0; i--) {
            long q = value / 10L;
            out.setByte(base + i, (byte) ('0' + (value - q * 10L)));
            value = q;
        }
        out.writerIndex(base + digits);
    }

    private static int decimalLength(long positiveValue) {
        long p = 10L;
        for (int i = 1; i < 19; i++) {
            if (positiveValue < p) {
                return i;
            }
            p *= 10L;
        }
        return 19;
    }
}
