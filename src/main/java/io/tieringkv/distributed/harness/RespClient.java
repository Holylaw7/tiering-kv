package io.tieringkv.distributed.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 RESP2 客户端（ADR-0322 M4 增强）：真实客户端 Jepsen 链路用，
 * 支持 SET/GET。仅 ASCII 参数（harness 键值为 ASCII）。
 */
public final class RespClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RespClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public void put(String key, String value) throws IOException {
        send("SET", key, value);
        String response = readResponse();
        if (!"OK".equals(response)) {
            throw new IOException("SET failed: " + response);
        }
    }

    /** GET：命中返回值，未命中返回 null。 */
    public String get(String key) throws IOException {
        send("GET", key);
        return readResponse();
    }

    private void send(String name, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length + 1).append("\r\n");
        appendBulk(sb, name);
        for (String arg : args) {
            appendBulk(sb, arg);
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void appendBulk(StringBuilder sb, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sb.append('$').append(bytes.length).append("\r\n")
                .append(value).append("\r\n");
    }

    private String readResponse() throws IOException {
        int first = in.read();
        if (first == -1) {
            throw new IOException("connection closed");
        }
        return switch ((char) first) {
            case '+' -> readLine();
            case '-' -> throw new IOException("ERR " + readLine());
            case '$' -> readBulk();
            default -> throw new IOException(
                    "unexpected response type");
        };
    }

    private String readBulk() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length == -1) {
            return null;
        }
        byte[] bytes = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(bytes, read, length - read);
            if (n == -1) {
                throw new IOException("truncated bulk");
            }
            read += n;
        }
        readLine(); // 尾部 CRLF
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // '\n'
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
