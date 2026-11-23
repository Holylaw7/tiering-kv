package io.tieringkv.observability;

import io.tieringkv.cache.block.CacheStatistics;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RuntimeConfigBridge;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.tiering.StorageMetrics;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 里程碑 M4（C1）：数据面 /healthz、/readyz、/metrics 端点与 SLOWLOG 慢查询日志验证。
 */
class ObservabilityEndpointsAndSlowLogTest {

    @Test
    void slowLogCapturesCommandsRedactsSecretsAndSupportsGetLenReset() {
        SlowLog slowLog = new SlowLog(0, 2); // threshold=0 记录全部命令
        AclPolicy acl = new AclPolicy();
        CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                () -> "# Server\r\n", Map.of(), null, null, null,
                acl, RuntimeConfigBridge.standaloneDefaults(), slowLog);
        CommandEngine engine = new CommandEngine(registry, MemTable.create(), null, null, acl, slowLog);

        engine.execute(cmd("SET", "k1", "v1"));
        engine.execute(cmd("AUTH", "super-secret-pw"));
        engine.execute(cmd("GET", "k1"));

        // maxLen=2，最新两条为 GET 与 AUTH（敏感参数脱敏）
        assertThat(engine.execute(cmd("SLOWLOG", "LEN"))).isEqualTo(new RespInteger(2));
        RespArray rows = (RespArray) engine.execute(cmd("SLOWLOG", "GET", "5"));
        assertThat(rows.values()).hasSize(2);
        String rendered = dumpArgs((RespArray) rows.values().get(0)) + "|" + dumpArgs((RespArray) rows.values().get(1));
        assertThat(rendered).doesNotContain("super-secret-pw");
        assertThat(rendered).contains("<redacted>");

        assertThat(engine.execute(cmd("SLOWLOG", "RESET"))).isEqualTo(new RespSimpleString("OK"));
        assertThat(engine.execute(cmd("SLOWLOG", "LEN"))).isEqualTo(new RespInteger(0));
    }

    @Test
    void managementHttpServerExposesHealthReadyAndPrometheusMetrics() throws Exception {
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.connectionOpened();
        metrics.requestStarted();
        metrics.requestCompleted(2_500_000L);

        StorageMetrics storageMetrics = new StorageMetrics();
        storageMetrics.setMemoryGauges(1024, 4096, 12);
        storageMetrics.publishColdSnapshot(3, 8192, 50, 0, 0, 0, 0);

        SlowLog slowLog = new SlowLog(0, 16);
        slowLog.record("get", List.of("k".getBytes(StandardCharsets.UTF_8)), 500_000L);

        ObservabilityRegistry obs = new ObservabilityRegistry(
                new VectorMetricsRegistry(new VectorIndexStore(4)),
                new ReplicationMetricsRegistry(),
                new MultiModelMetricsRegistry(),
                new BackupMetricsRegistry());

        HttpClient http = HttpClient.newHttpClient();
        try (ManagementHttpServer mgmt = new ManagementHttpServer(
                "127.0.0.1", 0, metrics, storageMetrics,
                () -> new CacheStatistics.Snapshot(7, 3, 1, 2),
                obs, slowLog)) {
            mgmt.start();
            String base = "http://127.0.0.1:" + mgmt.boundPort();

            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("ok");

            HttpResponse<String> ready = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(200);

            HttpResponse<String> prom = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(prom.statusCode()).isEqualTo(200);
            assertThat(prom.body()).contains(
                    "tieringkv_connections 1",
                    "tieringkv_memory_used_bytes 1024",
                    "tieringkv_cold_sstables 3",
                    "tieringkv_block_cache_hits_total 7",
                    "tieringkv_slowlog_entries 1");

            metrics.stopAccepting();
            HttpResponse<String> draining = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(draining.statusCode()).isEqualTo(503);
        }
    }

    private static RespCommand cmd(String name, String... args) {
        List<byte[]> raw = java.util.Arrays.stream(args)
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .toList();
        return new RespCommand(name.toLowerCase(java.util.Locale.ROOT), raw);
    }

    private static String dumpArgs(RespArray entry) {
        RespArray args = (RespArray) entry.values().get(3);
        StringBuilder sb = new StringBuilder();
        for (var v : args.values()) {
            sb.append(new String(((RespBulkString) v).bytes(), StandardCharsets.UTF_8)).append(' ');
        }
        return sb.toString();
    }
}
