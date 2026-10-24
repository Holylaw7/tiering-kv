package io.tieringkv.webconsole.resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/** 单条 TCP 连接的 RESP 客户端（非线程安全，由连接池管理）。 */
public final class RespClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private RespClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RespClient open(String host, int port, int connectTimeoutMs,
                                  int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        try {
            return new RespClient(socket);
        } catch (IOException ex) {
            socket.close();
            throw ex;
        }
    }

    /** 执行命令并返回应答（错误应答也正常返回，由调用方判定）。 */
    public synchronized RespReply execute(List<byte[]> args) throws IOException {
        out.write(RespCodec.encodeCommand(args));
        out.flush();
        return RespCodec.read(in);
    }

    public synchronized RespReply execute(String... args) throws IOException {
        out.write(RespCodec.encodeCommand(args));
        out.flush();
        return RespCodec.read(in);
    }

    public boolean isUsable() {
        return socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭阶段忽略
        }
    }
}
