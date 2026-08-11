package io.tieringkv.runtime;

import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Redis RESP 网关运行时（ADR-0093）：SET/GET 自动事务（最小实现）。 */
public final class GatewayRuntime {

    private GatewayRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        RuntimeCoordinator coordinator = RuntimeCoordinator.start(options);
        int port = TxnRuntimeMain.port(options, "gateway-port", 6379);
        ExecutorService workers = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf("GatewayRuntime %s ready on %d%n",
                    coordinator.nodeId(), port);
            while (true) {
                Socket socket = server.accept();
                workers.submit(() -> serve(coordinator, socket));
            }
        }
    }

    private static void serve(RuntimeCoordinator coordinator, Socket socket) {
        try (socket; BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0) {
                    continue;
                }
                String command = parts[0].toLowerCase(Locale.ROOT);
                if ("set".equals(command) && parts.length >= 3) {
                    Transaction txn = coordinator.router().begin();
                    txn.put(parts[1].getBytes(StandardCharsets.UTF_8),
                            parts[2].getBytes(StandardCharsets.UTF_8));
                    coordinator.router().commit(txn);
                    out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                } else if ("get".equals(command) && parts.length == 2) {
                    io.tieringkv.transaction.rpc.TxnMessages.Response response =
                            coordinator.regions().get(0).get(
                                    parts[1].getBytes(
                                            StandardCharsets.UTF_8)).join();
                    if (response.succeeded() && response.message() != null
                            && !response.message().isEmpty()) {
                        byte[] value = response.message().getBytes(
                                StandardCharsets.UTF_8);
                        out.write(("$" + value.length + "\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(value);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    out.write("-ERR unknown command\r\n"
                            .getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (Exception ignored) {
            // 单连接错误不影响网关
        }
    }

}
