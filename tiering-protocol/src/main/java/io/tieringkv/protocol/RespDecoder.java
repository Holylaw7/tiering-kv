package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ByteProcessor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP2 零分配热路径解码器（ADR-0006 / B1 优化）。
 *
 * <p>增量解析：输入不足时回退 readerIndex 等待更多字节；单个缓冲区内的多条命令
 * （pipeline）循环解析直至输入不足。数组长度、Bulk 长度与整数直接在 {@link ByteBuf}
 * 上逐字节解析（零临时 {@code byte[]} / {@code String} 分配），并使用无栈单例控制信号
 * 避免 TCP 拆包时抓取 JVM 栈帧。
 */
public final class RespDecoder extends ByteToMessageDecoder {

    private static final int MAX_INLINE_LENGTH = 64 * 1024;
    private static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    private static final int MAX_ARRAY_LENGTH = 1024 * 1024;

    /** 单值解码（测试/工具用）：解码输入中的第一个 RESP 值。 */
    public static RespValue decodeSingle(ByteBuf in) {
        RespDecoder decoder = new RespDecoder();
        List<Object> out = new ArrayList<>(1);
        try {
            decoder.decode(null, in, out);
        } catch (Exception e) {
            throw new RespProtocolException(
                    "decode failed: " + e.getMessage());
        }
        return out.isEmpty() ? null : (RespValue) out.get(0);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (in.isReadable()) {
            int start = in.readerIndex();
            try {
                out.add(parseValue(in));
            } catch (IncompleteInputException e) {
                in.readerIndex(start);
                return;
            }
        }
    }

    private RespValue parseValue(ByteBuf in) throws IncompleteInputException {
        if (!in.isReadable()) {
            throw IncompleteInputException.INSTANCE;
        }
        int start = in.readerIndex();
        byte type = in.readByte();
        switch (type) {
            case '+':
                return new RespSimpleString(readLine(in));
            case '-':
                return new RespError(readLine(in));
            case ':':
                return new RespInteger(readAsciiLong(in, "invalid integer"));
            case '$':
                return parseBulkString(in);
            case '*':
                return parseArray(in);
            default:
                // 非 RESP 类型前缀按 inline 命令处理（如 PING\r\n）
                in.readerIndex(start);
                return parseInline(in);
        }
    }

    private RespValue parseBulkString(ByteBuf in) throws IncompleteInputException {
        long length = readAsciiLong(in, "invalid bulk length");
        if (length == -1) {
            return RespNull.BULK_STRING;
        }
        if (length < -1 || length > MAX_BULK_LENGTH) {
            throw new RespProtocolException("invalid bulk length");
        }
        if (in.readableBytes() < length + 2) {
            throw IncompleteInputException.INSTANCE;
        }
        byte[] data = new byte[(int) length];
        in.readBytes(data);
        expectCrLf(in);
        return new RespBulkString(data);
    }

    private RespValue parseArray(ByteBuf in) throws IncompleteInputException {
        long length = readAsciiLong(in, "invalid multibulk length");
        if (length == -1) {
            return RespNull.ARRAY;
        }
        if (length < -1 || length > MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("invalid multibulk length");
        }
        int capacity = (int) Math.min(length, 64);
        List<RespValue> values = new ArrayList<>(capacity);
        for (int i = 0; i < length; i++) {
            values.add(parseValue(in));
        }
        return new RespArray(values);
    }

    private RespValue parseInline(ByteBuf in) throws IncompleteInputException {
        String line = readLine(in);
        if (line.isBlank()) {
            throw new RespProtocolException("empty request");
        }
        String[] tokens = line.trim().split("\\s+");
        List<RespValue> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            values.add(new RespBulkString(token.getBytes(StandardCharsets.UTF_8)));
        }
        return new RespArray(values);
    }

    /**
     * 零分配直读 ASCII 有符号 64 位整数（以 CRLF 结尾），兼容前后空白。
     * 避免为每条 RESP 帧头分配临时 byte[] 与 String。
     */
    private long readAsciiLong(ByteBuf in, String error) throws IncompleteInputException {
        int start = in.readerIndex();
        int readable = in.readableBytes();
        int cr = in.forEachByte(start, readable, ByteProcessor.FIND_CR);
        if (cr < 0) {
            if (readable > MAX_INLINE_LENGTH) {
                throw new RespProtocolException("too big inline request");
            }
            throw IncompleteInputException.INSTANCE;
        }
        if (cr + 1 >= in.writerIndex()) {
            throw IncompleteInputException.INSTANCE;
        }
        if (in.getByte(cr + 1) != (byte) '\n') {
            throw new RespProtocolException("expected CRLF");
        }
        int lineLength = cr - start;
        if (lineLength > MAX_INLINE_LENGTH) {
            throw new RespProtocolException("too big inline request");
        }
        int p = start;
        int end = cr;
        while (p < end && in.getByte(p) <= ' ') {
            p++;
        }
        while (end > p && in.getByte(end - 1) <= ' ') {
            end--;
        }
        if (p >= end) {
            throw new RespProtocolException(error);
        }
        boolean negative = false;
        byte first = in.getByte(p);
        if (first == '-') {
            negative = true;
            p++;
        } else if (first == '+') {
            p++;
        }
        if (p >= end) {
            throw new RespProtocolException(error);
        }
        // 使用负数累计以精确覆盖 Long.MIN_VALUE（-9223372036854775808）
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10L;
        long result = 0L;
        while (p < end) {
            int digit = in.getByte(p++) - '0';
            if (digit < 0 || digit > 9) {
                throw new RespProtocolException(error);
            }
            if (result < multmin) {
                throw new RespProtocolException(error);
            }
            result *= 10L;
            if (result < limit + digit) {
                throw new RespProtocolException(error);
            }
            result -= digit;
        }
        in.readerIndex(cr + 2);
        return negative ? result : -result;
    }

    private String readLine(ByteBuf in) throws IncompleteInputException {
        int start = in.readerIndex();
        int readable = in.readableBytes();
        int cr = in.forEachByte(start, readable, ByteProcessor.FIND_CR);
        if (cr < 0) {
            if (readable > MAX_INLINE_LENGTH) {
                throw new RespProtocolException("too big inline request");
            }
            throw IncompleteInputException.INSTANCE;
        }
        if (cr + 1 >= in.writerIndex()) {
            throw IncompleteInputException.INSTANCE;
        }
        if (in.getByte(cr + 1) != (byte) '\n') {
            throw new RespProtocolException("expected CRLF");
        }
        int lineLength = cr - start;
        if (lineLength > MAX_INLINE_LENGTH) {
            throw new RespProtocolException("too big inline request");
        }
        String line = in.toString(start, lineLength, StandardCharsets.UTF_8);
        in.readerIndex(cr + 2);
        return line;
    }

    private void expectCrLf(ByteBuf in) {
        if (in.readByte() != (byte) '\r' || in.readByte() != (byte) '\n') {
            throw new RespProtocolException("expected CRLF after bulk data");
        }
    }

    /** 输入不足的内部无栈单例信号，避免拆包时抓取 JVM 栈帧。 */
    private static final class IncompleteInputException extends Exception {
        private static final long serialVersionUID = 1L;
        static final IncompleteInputException INSTANCE = new IncompleteInputException();

        private IncompleteInputException() {
            super(null, null, false, false);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
