package io.tieringkv.observability;

import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.storage.memory.MemoryManager;

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

    public ServerInfoProvider(MetricsRegistry metrics,
                              MemoryManager memory,
                              Supplier<Long> keyCount,
                              int port,
                              String version) {
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
    }

    /** 单个 section 的提供者（INFO &lt;section&gt; 使用）。 */
    public Map<String, Supplier<String>> sections() {
        Map<String, Supplier<String>> sections = new LinkedHashMap<>();
        sections.put("server", this::server);
        sections.put("clients", this::clients);
        sections.put("memory", this::memorySection);
        sections.put("stats", this::stats);
        sections.put("keyspace", this::keyspace);
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
