package io.tieringkv.benchmark.production;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 管道 RESP 压测客户端（ADR-0029）：只发送与跳过响应，最小化客户端开销。 */
public final class PipelinedBenchClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;

    public PipelinedBenchClient(int port) throws IOException {
        this.socket = new Socket("127.0.0.1", port);
        this.out = socket.getOutputStream();
        this.in = socket.getInputStream();
    }

    public void write(byte[] command) throws IOException {
        out.write(command);
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** 跳过一条响应（不解析内容）。 */
    public void skipResponse() throws IOException {
        int first = in.read();
        if (first == -1) {
            throw new IOException("connection closed");
        }
        switch ((char) first) {
            case '+':
            case '-':
            case ':':
                skipLine();
                return;
            case '$': {
                int length = Integer.parseInt(readLine());
                if (length >= 0) {
                    in.skipNBytes(length + 2);
                }
                return;
            }
            case '*': {
                int count = Integer.parseInt(readLine());
                for (int i = 0; i < count; i++) {
                    skipResponse();
                }
                return;
            }
            default:
                throw new IOException("unexpected response type: " + (char) first);
        }
    }

    private void skipLine() throws IOException {
        int b;
        do {
            b = in.read();
        } while (b != -1 && b != '\n');
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] line = buffer.toByteArray();
                int length = Math.max(0, line.length - 1);
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
