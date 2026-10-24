package io.tieringkv.webconsole.resp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP2 编解码。
 *
 * <p>编码：命令统一作为 bulk string 数组发送（与 redis-cli 一致）；
 * 解码：支持 + - : $ * 五种类型，递归解析嵌套数组。
 */
public final class RespCodec {

    private RespCodec() {
    }

    /** 把命令参数编码成 RESP 数组。 */
    public static byte[] encodeCommand(List<byte[]> args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeAscii(out, "*" + args.size() + "\r\n");
        for (byte[] arg : args) {
            writeAscii(out, "$" + arg.length + "\r\n");
            out.writeBytes(arg);
            writeAscii(out, "\r\n");
        }
        return out.toByteArray();
    }

    public static byte[] encodeCommand(String... args) {
        List<byte[]> encoded = new ArrayList<>(args.length);
        for (String arg : args) {
            encoded.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return encodeCommand(encoded);
    }

    /** 从流中读取一个完整应答。 */
    public static RespReply read(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            throw new EOFException("连接已被对端关闭");
        }
        return readBody(in, (char) prefix);
    }

    private static RespReply readBody(InputStream in, char prefix) throws IOException {
        switch (prefix) {
            case '+':
                return new RespReply.Simple(readLine(in));
            case '-':
                return new RespReply.Error(readLine(in));
            case ':':
                return new RespReply.Integer(parseLong(readLine(in)));
            case '$': {
                int length = (int) parseLong(readLine(in));
                if (length < 0) {
                    return new RespReply.Bulk(null);
                }
                byte[] payload = readFully(in, length);
                requireCrlf(in);
                return new RespReply.Bulk(payload);
            }
            case '*': {
                int count = (int) parseLong(readLine(in));
                if (count < 0) {
                    return new RespReply.Array(null);
                }
                List<RespReply> items = new ArrayList<>(Math.min(count, 1024));
                for (int i = 0; i < count; i++) {
                    int next = in.read();
                    if (next == -1) {
                        throw new EOFException("数组元素读取中断");
                    }
                    items.add(readBody(in, (char) next));
                }
                return new RespReply.Array(items);
            }
            default:
                throw new RespException("非法 RESP 前缀: '" + prefix + "'");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current == -1) {
                throw new EOFException("响应行读取中断");
            }
            if (previous == '\r' && current == '\n') {
                byte[] raw = buffer.toByteArray();
                return new String(raw, 0, Math.max(0, raw.length - 1), StandardCharsets.UTF_8);
            }
            buffer.write(current);
            previous = current;
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read == -1) {
                throw new EOFException("批量字符串读取中断，期望 " + length + " 字节");
            }
            offset += read;
        }
        return data;
    }

    private static void requireCrlf(InputStream in) throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new RespException("批量字符串缺少 CRLF 结尾");
        }
    }

    private static long parseLong(String raw) throws RespException {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new RespException("非法数字: " + raw, ex);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
