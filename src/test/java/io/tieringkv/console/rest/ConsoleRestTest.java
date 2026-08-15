package io.tieringkv.console.rest;

import io.tieringkv.console.ConsoleApi;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.observability.ObservabilityRegistry;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 控制台 REST 服务（ADR-0139）：HTTP 端点 + 令牌 RBAC。 */
class ConsoleRestTest {

    private ConsoleRestServer server;
    private CredentialManager credentials;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ConsoleRestServer start(boolean seeded) throws Exception {
        credentials = new CredentialManager();
        TenantRegistry tenants = new TenantRegistry();
        if (seeded) {
            tenants.register(new io.tieringkv.saas.ClusterTenant(
                    "t1", "prod", 3, 100));
        }
        ConsoleApi api = new ConsoleApi(tenants,
                new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        server = new ConsoleRestServer(0, api);
        server.start();
        return server;
    }

    private ConsoleRestServer startWithPrometheus() throws Exception {
        credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(), new AlertManager(List.of()),
                credentials);
        server = new ConsoleRestServer(0, api,
                () -> "tiering_prometheus_probe 1\n");
        server.start();
        return server;
    }

    private String adminToken() {
        return credentials.issue(Role.ADMIN, 60_000);
    }

    @Test
    void tenantsEndpointWithToken() throws Exception {
        start(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/tenants"))
                .header("Authorization", "Bearer " + adminToken())
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("t1");
    }

    @Test
    void tenantsWithoutTokenForbidden() throws Exception {
        start(false);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/tenants"))
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void prometheusEndpointWithToken() throws Exception {
        startWithPrometheus();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/metrics/prometheus"))
                .header("Authorization", "Bearer " + adminToken())
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("tiering_prometheus_probe 1");
    }

    @Test
    void prometheusEndpointWithoutTokenForbidden() throws Exception {
        startWithPrometheus();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/metrics/prometheus"))
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void createTenantPost() throws Exception {
        start(false);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/tenants"))
                .header("Authorization", "Bearer " + adminToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":\"t7\"}"))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void metricsEndpoint() throws Exception {
        start(false);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/metrics"))
                .header("Authorization", "Bearer " + adminToken())
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void alertsEndpoint() throws Exception {
        start(false);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:"
                        + server.port() + "/alerts"))
                .header("Authorization", "Bearer " + adminToken())
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "port {0}")
    @ValueSource(ints = {0, 0})
    void parameterizedStart(int ignored) throws Exception {
        start(false);
        assertThat(server.port()).isGreaterThan(0);
    }
}
