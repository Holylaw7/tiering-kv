package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP2 解码器（ADR-0006）。
 *
 * <p>增量解析：输入不足时回退 readerIndex 等待更多字节；单个缓冲区内的多条命令
 * （pipeline）循环解析直至输入不足。协议错误抛出 {@link RespProtocolException}，
 * 由管道统一转换为错误响应并关闭连接。
 */
public final class RespDecoder extends ByteToMessageDecoder {

    private static final int MAX_INLINE_LENGTH = 64 * 1024;
    private static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    private static final int MAX_ARRAY_LENGTH = 1024 * 1024;

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
            throw new IncompleteInputException();
        }
        int start = in.readerIndex();
        byte type = in.readByte();
        switch (type) {
            case '+':
                return new RespSimpleString(readLine(in));
            case '-':
                return new RespError(readLine(in));
            case ':':
                return new RespInteger(parseLong(readLine(in), "invalid integer"));
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
        long length = parseLong(readLine(in), "invalid bulk length");
        if (length == -1) {
            return RespNull.BULK_STRING;
        }
        if (length < -1 || length > MAX_BULK_LENGTH) {
            throw new RespProtocolException("invalid bulk length");
        }
        if (in.readableBytes() < length + 2) {
            throw new IncompleteInputException();
        }
        byte[] data = new byte[(int) length];
        in.readBytes(data);
        expectCrLf(in);
        return new RespBulkString(data);
    }

    private RespValue parseArray(ByteBuf in) throws IncompleteInputException {
        long length = parseLong(readLine(in), "invalid multibulk length");
        if (length == -1) {
            return RespNull.ARRAY;
        }
        if (length < -1 || length > MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("invalid multibulk length");
        }
        List<RespValue> values = new ArrayList<>((int) length);
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

    private String readLine(ByteBuf in) throws IncompleteInputException {
        int start = in.readerIndex();
        // ByteProcessor 语义：返回 true 继续扫描、false 停止；在第一个 '\r' 处停止
        int cr = in.forEachByte(start, in.readableBytes(), (byte b) -> b != (byte) '\r');
        if (cr < 0) {
            if (in.readableBytes() > MAX_INLINE_LENGTH) {
                throw new RespProtocolException("too big inline request");
            }
            throw new IncompleteInputException();
        }
        if (cr + 1 >= in.writerIndex()) {
            throw new IncompleteInputException();
        }
        if (in.getByte(cr + 1) != (byte) '\n') {
            throw new RespProtocolException("expected CRLF");
        }
        int lineLength = cr - start;
        if (lineLength > MAX_INLINE_LENGTH) {
            throw new RespProtocolException("too big inline request");
        }
        byte[] line = new byte[lineLength];
        in.readBytes(line);
        in.skipBytes(2);
        return new String(line, StandardCharsets.UTF_8);
    }

    private void expectCrLf(ByteBuf in) {
        if (in.readByte() != (byte) '\r' || in.readByte() != (byte) '\n') {
            throw new RespProtocolException("expected CRLF after bulk data");
        }
    }

    private long parseLong(String text, String error) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new RespProtocolException(error);
        }
    }

    /** 输入不足的内部控制信号，不对外暴露。 */
    private static final class IncompleteInputException extends Exception {
    }
}
