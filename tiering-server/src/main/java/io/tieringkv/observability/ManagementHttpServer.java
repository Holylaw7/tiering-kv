package io.tieringkv.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tieringkv.cache.block.CacheStatistics;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.storage.tiering.StorageMetrics;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 轻量数据面 HTTP 管理端口（M4-C1）：基于 JDK 内置 HttpServer（零三方依赖），
 * 暴露 K8s 探针 {@code /healthz}、{@code /readyz} 与 Prometheus {@code /metrics}。
 */
public final class ManagementHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final MetricsRegistry metrics;
    private final StorageMetrics storageMetrics;
    private final Supplier<CacheStatistics.Snapshot> cacheStats;
    private final ObservabilityRegistry observability;
    private final SlowLog slowLog;
    private final WALManager walManager;

    public ManagementHttpServer(String host,
                                int port,
                                MetricsRegistry metrics,
                                StorageMetrics storageMetrics,
                                Supplier<CacheStatistics.Snapshot> cacheStats,
                                ObservabilityRegistry observability,
                                SlowLog slowLog) throws IOException {
        this(host, port, metrics, storageMetrics, cacheStats, observability, slowLog, null);
    }

    public ManagementHttpServer(String host,
                                int port,
                                MetricsRegistry metrics,
                                StorageMetrics storageMetrics,
                                Supplier<CacheStatistics.Snapshot> cacheStats,
                                ObservabilityRegistry observability,
                                SlowLog slowLog,
                                WALManager walManager) throws IOException {
        this.metrics = metrics;
        this.storageMetrics = storageMetrics;
        this.cacheStats = cacheStats;
        this.observability = observability;
        this.slowLog = slowLog;
        this.walManager = walManager;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 32);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "tiering-mgmt-http");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);
        this.server.createContext("/healthz", this::handleHealth);
        this.server.createContext("/readyz", this::handleReady);
        this.server.createContext("/metrics", this::handleMetrics);
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        boolean healthy = metrics == null || metrics.accepting();
        respond(ex, healthy ? 200 : 503, "text/plain; charset=utf-8",
                healthy ? "ok\n" : "shutting_down\n");
    }

    private void handleReady(HttpExchange ex) throws IOException {
        boolean ready = metrics == null || metrics.accepting();
        respond(ex, ready ? 200 : 503, "text/plain; charset=utf-8",
                ready ? "ready\n" : "not_ready\n");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        String body = renderPrometheus();
        respond(ex, 200, "text/plain; version=0.0.4; charset=utf-8", body);
    }

    public String renderPrometheus() {
        StringBuilder sb = new StringBuilder(1024);
        if (metrics != null) {
            MetricsRegistry.Snapshot s = metrics.snapshot();
            gauge(sb, "tieringkv_connections", "Current active RESP connections", s.connections());
            gauge(sb, "tieringkv_active_requests", "Current in-flight RESP commands", s.activeRequests());
            gauge(sb, "tieringkv_qps", "Average commands per second", s.qps());
            counter(sb, "tieringkv_errors_total", "Total RESP errors", s.errors());
            gauge(sb, "tieringkv_latency_p50_ms", "Command latency p50 in ms", s.p50Millis());
            gauge(sb, "tieringkv_latency_p95_ms", "Command latency p95 in ms", s.p95Millis());
            gauge(sb, "tieringkv_latency_p99_ms", "Command latency p99 in ms", s.p99Millis());
            gauge(sb, "tieringkv_latency_max_ms", "Command latency max in ms", s.maxMillis());
        }
        if (storageMetrics != null) {
            StorageMetrics.Snapshot st = storageMetrics.snapshot();
            gauge(sb, "tieringkv_memory_used_bytes", "Hot tier MemTable used bytes", st.memoryUsedBytes());
            gauge(sb, "tieringkv_memory_max_bytes", "Hot tier MemTable max bytes", st.memoryMaxBytes());
            gauge(sb, "tieringkv_memory_entries", "Hot tier MemTable entries", st.memoryEntryCount());
            gauge(sb, "tieringkv_cold_sstables", "Cold tier SSTable count", st.coldSstableCount());
            gauge(sb, "tieringkv_cold_disk_bytes", "Cold tier disk usage bytes", st.coldDiskUsage());
            gauge(sb, "tieringkv_cold_entries", "Cold tier entry count", st.coldEntryCount());
            counter(sb, "tieringkv_flush_total", "Total MemTable flush operations", st.flushCount());
            counter(sb, "tieringkv_migration_success_total", "Total successful cold migrations", st.migrationSuccess());
            counter(sb, "tieringkv_migration_failed_total", "Total failed cold migrations", st.migrationFailed());
            counter(sb, "tieringkv_compaction_total", "Total SSTable compactions", st.compactionCount());
        }
        if (cacheStats != null) {
            CacheStatistics.Snapshot c = cacheStats.get();
            counter(sb, "tieringkv_block_cache_hits_total", "BlockCache hits", c.hits());
            counter(sb, "tieringkv_block_cache_misses_total", "BlockCache misses", c.misses());
        }
        if (slowLog != null) {
            gauge(sb, "tieringkv_slowlog_entries", "Current slowlog buffered entries", slowLog.len());
        }
        if (walManager != null) {
            long batches = walManager.groupCommitBatches();
            long records = walManager.groupCommitRecords();
            double avgBatch = batches == 0 ? 0.0 : (double) records / batches;
            gauge(sb, "tieringkv_wal_shards", "Active WAL shard count", walManager.shardCount());
            sb.append("# HELP tieringkv_wal_io_backend Active WAL I/O backend indicator\n");
            sb.append("# TYPE tieringkv_wal_io_backend gauge\n");
            sb.append("tieringkv_wal_io_backend{backend=\"").append(walManager.activeIoBackend())
                    .append("\",preallocate=\"").append(walManager.preallocateEnabled()).append("\"} 1\n");
            counter(sb, "tieringkv_wal_write_batches_total", "Total WAL write batches submitted", batches);
            counter(sb, "tieringkv_wal_write_records_total", "Total WAL records written", records);
            gauge(sb, "tieringkv_wal_avg_batch_size", "Average WAL records per write batch", avgBatch);
            counter(sb, "tieringkv_wal_write_bytes_total", "Total WAL bytes written", walManager.totalBytesWritten());
            counter(sb, "tieringkv_wal_fsync_total", "Total WAL fdatasync/fsync invocations", walManager.totalFsyncCount());
            gauge(sb, "tieringkv_wal_fsync_max_ms", "Max WAL fsync duration in ms",
                    walManager.maxFsyncNanos() / 1_000_000.0);
        }
        if (observability != null) {
            String extra = observability.prometheusText();
            if (extra != null && !extra.isBlank()) {
                sb.append(extra);
                if (!extra.endsWith("\n")) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, long val) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(val).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, double val) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(String.format(Locale.ROOT, "%.3f", val)).append('\n');
    }

    private static void counter(StringBuilder sb, String name, String help, long val) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(val).append('\n');
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
