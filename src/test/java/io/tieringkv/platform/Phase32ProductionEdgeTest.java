package io.tieringkv.platform;

import io.tieringkv.console.rest.ConsoleRestServer;
import io.tieringkv.console.ConsoleApi;
import io.tieringkv.gateway.ConflictAuditLog;
import io.tieringkv.gateway.RegionAffinityRouter;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.sharding.auto.ConcurrentReshardExecutor;
import io.tieringkv.sql.txn.SqlTxn2PcExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 32 生产边缘：SQL2PC/并发重分片/冲突审计/REST 参数矩阵。 */
class Phase32ProductionEdgeTest {

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void sql2pcProductionVolume(int writes) {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        executor.begin(token);
        for (int i = 0; i < writes; i++) {
            executor.write(bytes("k" + i), bytes("v"), false);
        }
        assertThat(executor.commit()).isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void concurrentReshardVolume(int count) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(4, 100);
        assertThat(executor.execute(source, target)).isEqualTo(count);
        assertThat(target).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void conflictAuditVolume(int count) {
        ConflictAuditLog audit = new ConflictAuditLog();
        for (int i = 0; i < count; i++) {
            audit.audit("r1", "k" + i, "r2");
        }
        assertThat(audit.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            20, 30, 50, 100, 200})
    void affinityRegionVolume(int count) {
        List<String> regions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add("r" + i);
        }
        RegionAffinityRouter router = new RegionAffinityRouter(regions);
        assertThat(router.route(bytes("k"))).isIn(regions);
    }

    @Test
    void restServerMetricsRoundTrip() throws Exception {
        CredentialManager credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(), new AlertManager(List.of()),
                credentials);
        try (ConsoleRestServer server = new ConsoleRestServer(0, api)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:"
                            + server.port() + "/metrics"))
                    .header("Authorization", "Bearer "
                            + credentials.issue(Role.READER, 60_000))
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
