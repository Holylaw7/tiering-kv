package io.tieringkv.console.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tieringkv.console.ConsoleApi;
import io.tieringkv.saas.ClusterTenant;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** 控制台 REST 服务（ADR-0139）：JDK HttpServer + 令牌 RBAC。 */
public final class ConsoleRestServer implements AutoCloseable {

    private final HttpServer server;

    public ConsoleRestServer(int port, ConsoleApi api)
            throws IOException {
        this(port, api, null);
    }

    /** 可观测性收口（ADR-0344）：可选 Prometheus 文本提供者。 */
    public ConsoleRestServer(int port, ConsoleApi api,
                             Supplier<String> prometheusProvider)
            throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/tenants", exchange ->
                handleTenants(exchange, api));
        server.createContext("/metrics", exchange ->
                handleMetrics(exchange, api));
        server.createContext("/metrics/prometheus", exchange ->
                handlePrometheus(exchange, api, prometheusProvider));
        server.createContext("/alerts", exchange ->
                handleAlerts(exchange, api));
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handleTenants(HttpExchange exchange,
                                      ConsoleApi api) {
        try {
            String token = token(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, api.listTenants(token).toString());
            } else if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody()
                        .readAllBytes(), StandardCharsets.UTF_8);
                String tenantId = body.replaceAll("\\D", "");
                api.createTenant(token, new ClusterTenant(
                        "t" + tenantId, "c" + tenantId, 3, 100));
                respond(exchange, 201, "created");
            } else {
                respond(exchange, 405, "method not allowed");
            }
        } catch (Exception e) {
            respond(exchange, 403, "forbidden");
        }
    }

    private static void handleMetrics(HttpExchange exchange,
                                      ConsoleApi api) {
        try {
            respond(exchange, 200, api.metrics(token(exchange))
                    .toString());
        } catch (Exception e) {
            respond(exchange, 403, "forbidden");
        }
    }

    private static void handlePrometheus(HttpExchange exchange,
                                         ConsoleApi api,
                                         Supplier<String> provider) {
        try {
            api.metrics(token(exchange)); // 鉴权：无 token 抛异常 → 403
            String text = provider == null ? "" : provider.get();
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; version=0.0.4");
            respond(exchange, 200, text);
        } catch (Exception e) {
            respond(exchange, 403, "forbidden");
        }
    }

    private static void handleAlerts(HttpExchange exchange,
                                     ConsoleApi api) {
        try {
            respond(exchange, 200, api.alerts(token(exchange)).toString());
        } catch (Exception e) {
            respond(exchange, 403, "forbidden");
        }
    }

    private static String token(HttpExchange exchange) {
        String header = exchange.getRequestHeaders()
                .getFirst("Authorization");
        return header == null ? "" : header.replace("Bearer ", "");
    }

    private static void respond(HttpExchange exchange, int status,
                                String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
