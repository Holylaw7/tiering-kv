package io.tieringkv.runtime;

import io.tieringkv.ServerVersion;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RuntimeConfigBridge;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.observability.tracing.Tracer;
import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;
import io.tieringkv.security.AclPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis RESP 网关运行时（ADR-0093 + M2-A1）：
 * 复用 Netty {@link TieringKvServer} 与全量 {@link CommandEngine}，
 * 经 {@link CoordinatorStorageEngine} 将请求路由至多 Region 分布式事务协调器。
 */
public final class GatewayRuntime {

    private GatewayRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        start(options, null);
    }

    /** 启动 Netty RESP 网关并阻塞至进程退出。 */
    public static void start(Map<String, String> options,
                             Tracer tracer) throws Exception {
        RuntimeCoordinator coordinator = RuntimeCoordinator.start(options);
        int port = TxnRuntimeMain.port(options, "gateway-port", 6379);
        String password = options.get("password");
        RunningGateway gateway = startServer(coordinator, port, password);
        System.out.printf("GatewayRuntime %s ready on %d%n",
                coordinator.nodeId(), gateway.port());
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
        gateway.awaitTermination();
    }

    /** 可测试启停句柄：直接绑定已启动的 RuntimeCoordinator。 */
    public static RunningGateway startServer(RuntimeCoordinator coordinator,
                                             int port,
                                             String password) throws InterruptedException {
        CoordinatorStorageEngine storage = new CoordinatorStorageEngine(coordinator);
        AclPolicy aclPolicy = new AclPolicy();
        if (password != null && !password.isBlank()) {
            aclPolicy.configureRequirePass(password);
        }
        MetricsRegistry metrics = new MetricsRegistry();
        ServerConfig config = new ServerConfig("0.0.0.0", port, 10_000, 0, password,
                ServerConfig.TlsConfig.disabled());
        TieringKvServer.RuntimeOptions options =
                new TieringKvServer.RuntimeOptions(config.maxClients(), config.idleTimeoutSeconds());
        RuntimeConfigBridge runtimeConfig = RuntimeConfigBridge.builder()
                .maxClients(options::maxClients, options::setMaxClients)
                .timeout(options::idleTimeoutSeconds, options::setIdleTimeoutSeconds)
                .requirePass(aclPolicy::requiresAuthForAnonymous, aclPolicy::configureRequirePass)
                .build();
        ServerInfoProvider info = new ServerInfoProvider(
                metrics, null, storage::size, port, ServerVersion.current());
        KeyShardExecutor executor = new KeyShardExecutor(
                Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
                "gateway-resp-" + coordinator.nodeId());
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefaultWithRuntime(
                        info::render, info.sections(), null, null, null, aclPolicy, runtimeConfig),
                storage, executor, null, aclPolicy);
        TieringKvServer server = new TieringKvServer(config, engine, metrics, options);
        server.start();
        return new RunningGateway(coordinator, executor, server);
    }

    public static final class RunningGateway implements AutoCloseable {
        private final RuntimeCoordinator coordinator;
        private final KeyShardExecutor executor;
        private final TieringKvServer server;

        RunningGateway(RuntimeCoordinator coordinator, KeyShardExecutor executor, TieringKvServer server) {
            this.coordinator = coordinator;
            this.executor = executor;
            this.server = server;
        }

        public int port() {
            return server.boundPort();
        }

        public void awaitTermination() throws InterruptedException {
            server.awaitTermination();
        }

        @Override
        public void close() {
            server.shutdown();
            executor.close();
            try {
                coordinator.close();
            } catch (Exception ignored) {
                // 停机路径尽力清理
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
