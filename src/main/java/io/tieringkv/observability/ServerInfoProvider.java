package io.tieringkv.observability;

import io.tieringkv.cache.block.CacheStatistics;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.tiering.StorageMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 INFO 渲染（ADR-0355）。
 *
 * <p>在既有 {@link MetricsRegistry#infoText()} 的键（connections /
 * active_requests / qps / avg_latency_ms / errors）保持兼容的前提下，
 * 补齐 Redis 兼容的标准 section：server / clients / memory / stats /
 * keyspace，供 Web 控制台解析真实运行态，避免控制台自行猜测指标。
 */
public final class ServerInfoProvider {

    private final MetricsRegistry metrics;
    private final MemoryManager memory;
    private final Supplier<Long> keyCount;
    private final int port;
    private final String version;
    private final long startedAt = System.currentTimeMillis();
    private final String runId = UUID.randomUUID().toString();
    private final StorageMetrics storageMetrics;
    private final Supplier<CacheStatistics.Snapshot> cacheStats;
    private final Runnable refreshGauges;

    public ServerInfoProvider(MetricsRegistry metrics,
                              MemoryManager memory,
                              Supplier<Long> keyCount,
                              int port,
                              String version) {
        this(metrics, memory, keyCount, port, version, null, null, null);
    }

    /**
     * 带存储分层指标的构造（TD-095）：向 INFO 追加 `# Tiering` 段落，
     * 暴露 MemTable / SSTable / Flush / 迁移 / BlockCache 的真实计数。
     */
    public ServerInfoProvider(MetricsRegistry metrics,
                              MemoryManager memory,
                              Supplier<Long> keyCount,
                              int port,
                              String version,
                              StorageMetrics storageMetrics,
                              Supplier<CacheStatistics.Snapshot> cacheStats,
                              Runnable refreshGauges) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics required");
        }
        if (keyCount == null) {
            throw new IllegalArgumentException("keyCount required");
        }
        this.metrics = metrics;
        this.memory = memory;
        this.keyCount = keyCount;
        this.port = port;
        this.version = version == null ? "unknown" : version;
        this.storageMetrics = storageMetrics;
        this.cacheStats = cacheStats;
        this.refreshGauges = refreshGauges;
    }

    /** 单个 section 的提供者（INFO &lt;section&gt; 使用）。 */
    public Map<String, Supplier<String>> sections() {
        Map<String, Supplier<String>> sections = new LinkedHashMap<>();
        sections.put("server", this::server);
        sections.put("clients", this::clients);
        sections.put("memory", this::memorySection);
        sections.put("stats", this::stats);
        sections.put("keyspace", this::keyspace);
        if (storageMetrics != null) {
            sections.put("tiering", this::tiering);
        }
        return Map.copyOf(sections);
    }

    /** 全量 INFO 文本（CRLF 分隔，与 Redis 一致）。 */
    public String render() {
        StringBuilder out = new StringBuilder(768);
        for (Supplier<String> section : sections().values()) {
            out.append(section.get());
        }
        return out.toString();
    }

    private long uptimeSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
    }

    private String server() {
        long uptime = uptimeSeconds();
        return "# Server\r\n"
                + "redis_version:" + version + "\r\n"
                + "tieringkv_version:" + version + "\r\n"
                + "redis_mode:standalone\r\n"
                + "os:" + System.getProperty("os.name", "unknown") + "\r\n"
                + "arch_bits:" + System.getProperty("os.arch", "unknown") + "\r\n"
                + "process_id:" + ProcessHandle.current().pid() + "\r\n"
                + "run_id:" + runId + "\r\n"
                + "tcp_port:" + port + "\r\n"
                + "uptime_in_seconds:" + uptime + "\r\n"
                + "uptime_in_days:" + (uptime / 86_400) + "\r\n"
                + "jdk_version:" + System.getProperty("java.version", "unknown")
                + "\r\n";
    }

    private String clients() {
        MetricsRegistry.Snapshot snapshot = metrics.snapshot();
        return "# Clients\r\n"
                + "connected_clients:" + snapshot.connections() + "\r\n"
                + "active_requests:" + snapshot.activeRequests() + "\r\n";
    }

    private String memorySection() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        long jvmUsed = bean.getHeapMemoryUsage().getUsed();
        long jvmMax = bean.getHeapMemoryUsage().getMax();
        long engineUsed = memory == null ? 0 : memory.usedBytes();
        long engineMax = memory == null ? 0 : memory.maxBytes();
        return "# Memory\r\n"
                + "used_memory:" + engineUsed + "\r\n"
                + "used_memory_human:" + human(engineUsed) + "\r\n"
                + "maxmemory:" + engineMax + "\r\n"
                + "maxmemory_human:" + human(engineMax) + "\r\n"
                + "used_memory_jvm:" + jvmUsed + "\r\n"
                + "used_memory_jvm_human:" + human(jvmUsed) + "\r\n"
                + "max_jvm_heap:" + jvmMax + "\r\n"
                + "max_jvm_heap_human:" + human(jvmMax) + "\r\n"
                + "mem_allocator:jvm-" + runtime.availableProcessors() + "cpu\r\n";
    }

    private String stats() {
        MetricsRegistry.Snapshot snapshot = metrics.snapshot();
        return "# Stats\r\n"
                + "connections:" + snapshot.connections() + "\r\n"
                + "active_requests:" + snapshot.activeRequests() + "\r\n"
                + "qps:" + String.format(Locale.ROOT, "%.1f", snapshot.qps()) + "\r\n"
                + "avg_latency_ms:"
                + String.format(Locale.ROOT, "%.3f", snapshot.avgLatencyMs()) + "\r\n"
                + "errors:" + snapshot.errors() + "\r\n"
                + "instantaneous_ops_per_sec:"
                + String.format(Locale.ROOT, "%.1f", snapshot.qps()) + "\r\n"
                + "total_keys:" + keys() + "\r\n";
    }

    /**
     * 存储分层段落（TD-095）：全部来自 StorageMetrics / CacheStatistics，
     * 不推算、不估算，取不到就是 0。
     */
    private String tiering() {
        if (refreshGauges != null) {
            refreshGauges.run();
        }
        StorageMetrics.Snapshot snapshot = storageMetrics.snapshot();
        CacheStatistics.Snapshot cache = cacheStats == null
                ? new CacheStatistics.Snapshot(0, 0, 0, 0)
                : cacheStats.get();
        long hot = snapshot.memoryUsedBytes();
        long cold = snapshot.coldDiskUsage();
        long total = hot + cold;
        double coldShare = total == 0 ? 0 : cold / (double) total;
        long flushTotal = snapshot.flushCount();
        return "# Tiering\r\n"
                + "tiering_memtable_used_bytes:" + hot + "\r\n"
                + "tiering_memtable_max_bytes:" + snapshot.memoryMaxBytes() + "\r\n"
                + "tiering_memtable_entries:" + snapshot.memoryEntryCount() + "\r\n"
                + "tiering_cold_sstable_count:" + snapshot.coldSstableCount() + "\r\n"
                + "tiering_cold_disk_usage_bytes:" + cold + "\r\n"
                + "tiering_cold_share:" + String.format(Locale.ROOT, "%.4f", coldShare) + "\r\n"
                + "tiering_flush_count:" + flushTotal + "\r\n"
                + "tiering_flush_bytes:" + snapshot.flushBytes() + "\r\n"
                + "tiering_migration_pending:" + snapshot.migrationPending() + "\r\n"
                + "tiering_migration_success:" + snapshot.migrationSuccess() + "\r\n"
                + "tiering_migration_failed:" + snapshot.migrationFailed() + "\r\n"
                + "tiering_block_cache_hits:" + cache.hits() + "\r\n"
                + "tiering_block_cache_misses:" + cache.misses() + "\r\n"
                + "tiering_block_cache_evictions:" + cache.evictions() + "\r\n"
                + "tiering_block_cache_hit_rate:"
                + String.format(Locale.ROOT, "%.4f", cache.hitRate()) + "\r\n";
    }

    private String keyspace() {
        long keys = keys();
        if (keys == 0) {
            return "# Keyspace\r\n";
        }
        return "# Keyspace\r\n"
                + "db0:keys=" + keys + ",expires=0,avg_ttl=0\r\n";
    }

    private long keys() {
        Long value = keyCount.get();
        return value == null ? 0 : value;
    }

    /** 人类可读字节数（与 Redis used_memory_human 口径一致）。 */
    static String human(long bytes) {
        double value = bytes;
        String[] units = {"B", "K", "M", "G", "T"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f%s", value, units[unit]);
    }
}