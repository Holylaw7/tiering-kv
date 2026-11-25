package io.tieringkv.operator.k8s;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Operator 零依赖健康探针端点（v5.4.0 A2）：
 * <ul>
 *   <li>{@code GET /healthz}：进程存活探针，恒返回 {@code 200 OK}；</li>
 *   <li>{@code GET /readyz}：就绪探针，已启动且持有主控权时返回 {@code 200 OK}，否则 {@code 503}。</li>
 * </ul>
 */
public final class OperatorHealthServer implements AutoCloseable {

    private static final byte[] HEALTH_BODY =
            "{\"status\":\"UP\"}\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] READY_BODY =
            "{\"status\":\"READY\",\"ready\":true}\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_READY_BODY =
            "{\"status\":\"NOT_READY\",\"ready\":false}\n".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final ExecutorService executor;
    private final BooleanSupplier readySupplier;

    public OperatorHealthServer(int port, BooleanSupplier readySupplier)
            throws IOException {
        if (readySupplier == null) {
            throw new IllegalArgumentException("readySupplier required");
        }
        this.readySupplier = readySupplier;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "tiering-kv-operator-health");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(this.executor);
        this.server.createContext("/healthz", this::handleHealth);
        this.server.createContext("/readyz", this::handleReady);
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, HEALTH_BODY);
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        boolean ready = readySupplier.getAsBoolean();
        writeJson(exchange, ready ? 200 : 503,
                ready ? READY_BODY : NOT_READY_BODY);
    }

    private static void writeJson(HttpExchange exchange, int status,
                                  byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
