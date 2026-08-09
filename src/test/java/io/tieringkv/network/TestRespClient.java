package io.tieringkv.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 RESP2 测试客户端：发送原始字节并解析单个响应。
 * 仅用于集成测试（非产品代码）。
 */
public final class TestRespClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public TestRespClient(int port) throws IOException {
        this.socket = new Socket("127.0.0.1", port);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** 构造 ASCII 命令请求（仅适用于 ASCII 参数）。 */
    public static String command(String name, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length + 1).append("\r\n");
        appendBulk(sb, name);
        for (String arg : args) {
            appendBulk(sb, arg);
        }
        return sb.toString();
    }

    private static void appendBulk(StringBuilder sb, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sb.append('$').append(bytes.length).append("\r\n").append(value).append("\r\n");
    }

    public void send(String wire) throws IOException {
        sendBytes(wire.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBytes(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /**
     * 解析一个响应，返回规范化文本：
     * {@code +OK} / {@code -ERR ...} / {@code :1} / {@code $len\r\nvalue\r\n} / {@code $-1}。
     */
    public String readResponse() throws IOException {
        int first = in.read();
        if (first == -1) {
            throw new IOException("connection closed");
        }
        char type = (char) first;
        switch (type) {
            case '+':
            case '-':
            case ':':
                return type + readLine();
            case '$': {
                int length = Integer.parseInt(readLine());
                if (length == -1) {
                    return "$-1";
                }
                byte[] data = in.readNBytes(length);
                readLine(); // 尾部 CRLF
                return "$" + length + "\r\n" + new String(data, StandardCharsets.UTF_8) + "\r\n";
            }
            case '*':
                return "*" + readLine();
            default:
                throw new IOException("unexpected response type: " + type);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] line = buffer.toByteArray();
                int length = Math.max(0, line.length - 1); // 去掉 \r
                return new String(line, 0, length, StandardCharsets.UTF_8);
            }
            buffer.write(b);
        }
        throw new IOException("connection closed while reading line");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
