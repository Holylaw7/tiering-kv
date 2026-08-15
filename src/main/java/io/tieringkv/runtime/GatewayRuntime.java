package io.tieringkv.runtime;

import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis RESP 网关运行时（ADR-0093）：SET/GET 自动事务（最小实现）。
 *
 * <p>P3 真实 Runner 门禁发现：此网关原先按行解析命令，无法处理
 * RESP 数组（RespClient 的 {@code *3\r\n$3\r\nSET...} 被当成未知
 * 命令）；现改为 RESP2 数组解析 + 标准响应编码（ADR-0343 修正）。
 */
public final class GatewayRuntime {

    private GatewayRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        RuntimeCoordinator coordinator = RuntimeCoordinator.start(options);
        int port = TxnRuntimeMain.port(options, "gateway-port", 6379);
        boolean virtualThreads = "true".equals(options.get(
                "virtual-threads"));
        ExecutorService workers = createWorkerExecutor(virtualThreads);
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf("GatewayRuntime %s ready on %d%n",
                    coordinator.nodeId(), port);
            while (true) {
                Socket socket = server.accept();
                workers.submit(() -> serve(coordinator, socket));
            }
        }
    }

    /**
     * 工作执行器（ADR-0331，TD-002）：JDK 21 反射创建虚拟线程执行器，
     * JDK 17 回退 cached pool（POC 开关 --virtual-threads true）。
     */
    static ExecutorService createWorkerExecutor(boolean virtualThreads) {
        if (!virtualThreads) {
            return Executors.newCachedThreadPool();
        }
        try {
            return (ExecutorService) Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor")
                    .invoke(null);
        } catch (ReflectiveOperationException e) {
            return Executors.newCachedThreadPool();
        }
    }

    private static void serve(RuntimeCoordinator coordinator, Socket socket) {
        try (socket; BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            String[] parts;
            while ((parts = parseCommand(in)) != null) {
                String command = parts[0].toLowerCase(Locale.ROOT);
                if ("set".equals(command) && parts.length >= 3) {
                    Transaction txn = coordinator.router().begin();
                    txn.put(parts[1].getBytes(StandardCharsets.UTF_8),
                            parts[2].getBytes(StandardCharsets.UTF_8));
                    coordinator.router().commit(txn);
                    writeSimple(out, "OK");
                } else if ("get".equals(command) && parts.length == 2) {
                    io.tieringkv.transaction.rpc.TxnMessages.Response response =
                            coordinator.regions().get(0).get(
                                    parts[1].getBytes(
                                            StandardCharsets.UTF_8)).join();
                    if (response.succeeded() && response.message() != null
                            && !response.message().isEmpty()) {
                        writeBulk(out, response.message().getBytes(
                                StandardCharsets.UTF_8));
                    } else {
                        writeNullBulk(out);
                    }
                } else if ("ping".equals(command) && parts.length == 1) {
                    writeSimple(out, "PONG");
                } else {
                    writeError(out, "ERR unknown command");
                }
                out.flush();
            }
        } catch (Exception ignored) {
            // 单连接错误不影响网关
        }
    }

    /**
     * 解析 RESP2 命令数组（ADR-0343）：{@code *N} 后跟 N 个
     * {@code $len\r\n<bytes>\r\n} bulk。EOF 返回 null（连接关闭）。
     * 非数组/截断/非法长度抛 IOException，由 serve 的异常边界兜底。
     */
    static String[] parseCommand(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) {
            return null;
        }
        if (!line.startsWith("*")) {
            throw new IOException("expected RESP array, got: " + line);
        }
        int argc;
        try {
            argc = Integer.parseInt(line.substring(1));
        } catch (NumberFormatException e) {
            throw new IOException("invalid RESP array length", e);
        }
        if (argc <= 0) {
            throw new IOException("invalid RESP array length: " + argc);
        }
        String[] parts = new String[argc];
        for (int i = 0; i < argc; i++) {
            String lenLine = in.readLine();
            if (lenLine == null || !lenLine.startsWith("$")) {
                throw new IOException("expected RESP bulk string");
            }
            int len;
            try {
                len = Integer.parseInt(lenLine.substring(1));
            } catch (NumberFormatException e) {
                throw new IOException("invalid RESP bulk length", e);
            }
            if (len < 0) {
                throw new IOException("negative RESP bulk length: " + len);
            }
            char[] buf = new char[len];
            int read = 0;
            while (read < len) {
                int n = in.read(buf, read, len - read);
                if (n == -1) {
                    throw new IOException("truncated RESP bulk string");
                }
                read += n;
            }
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("malformed RESP bulk terminator");
            }
            parts[i] = new String(buf);
        }
        return parts;
    }

    static void writeSimple(OutputStream out, String value)
            throws IOException {
        out.write(('+' + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static void writeError(OutputStream out, String message)
            throws IOException {
        out.write(('-' + message + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static void writeBulk(OutputStream out, byte[] value)
            throws IOException {
        out.write(("$" + value.length + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    static void writeNullBulk(OutputStream out) throws IOException {
        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
    }

}
