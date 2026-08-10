package io.tieringkv.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 应用配置（ADR-0034）：YAML 加载 + 默认值，启动打印生效配置。 */
public record TieringConfig(
        Server server,
        Workers workers,
        Memory memory,
        Wal wal,
        Cache cache,
        Tiering tiering,
        Gc gc) {

    public record Server(String host, int port) {
    }

    public record Workers(int shardCount, int flushWorkers, int migrationWorkers) {
    }

    public record Memory(long maxBytes) {
    }

    public record Wal(String fsyncPolicy, long maxSegmentBytes) {
    }

    public record Cache(int blockCacheCapacity, long hotKeyWindowMillis, long hotKeyThreshold) {
    }

    public record Tiering(
            double lowWatermark,
            double highWatermark,
            double criticalWatermark,
            long backpressureTimeoutMillis,
            long drainTimeoutMillis) {
    }

    public record Gc(int batchSize, int workerCount, long maxMemoryBytes) {
    }

    public static TieringConfig defaults() {
        return new TieringConfig(
                new Server("0.0.0.0", 6379),
                new Workers(16, 1, 2),
                new Memory(1L << 30),
                new Wal("EVERY_SEC", 64L << 20),
                new Cache(1024, 1000, 1000),
                new Tiering(0.70, 0.85, 0.95, 1000, 5000),
                new Gc(4096, 4, 64L << 20));
    }

    @SuppressWarnings("unchecked")
    public static TieringConfig load(Path path) {
        if (!Files.exists(path)) {
            return defaults();
        }
        try {
            Map<String, Object> root = new Yaml().load(Files.readString(path));
            TieringConfig base = defaults();
            if (root == null) {
                return base;
            }
            Map<String, Object> server = map(root.get("server"));
            Map<String, Object> workers = map(root.get("worker"));
            Map<String, Object> memory = map(root.get("memory"));
            Map<String, Object> wal = map(root.get("wal"));
            Map<String, Object> cache = map(root.get("cache"));
            Map<String, Object> tiering = map(root.get("tiering"));
            Map<String, Object> gc = map(root.get("gc"));
            return new TieringConfig(
                    new Server(str(server.get("host"), base.server().host()),
                            num(server.get("port"), base.server().port())),
                    new Workers(num(workers.get("shard-count"), base.workers().shardCount()),
                            num(workers.get("flush-workers"), base.workers().flushWorkers()),
                            num(workers.get("migration-workers"), base.workers().migrationWorkers())),
                    new Memory(num(memory.get("limit"), base.memory().maxBytes())),
                    new Wal(str(wal.get("fsync-policy"), base.wal().fsyncPolicy()),
                            num(wal.get("max-segment-bytes"), base.wal().maxSegmentBytes())),
                    new Cache(num(cache.get("block-size"), base.cache().blockCacheCapacity()),
                            num(cache.get("hotkey-window-ms"), base.cache().hotKeyWindowMillis()),
                            num(cache.get("hotkey-threshold"), base.cache().hotKeyThreshold())),
                    new Tiering(dbl(tiering.get("low-watermark"), base.tiering().lowWatermark()),
                            dbl(tiering.get("high-watermark"), base.tiering().highWatermark()),
                            dbl(tiering.get("critical-watermark"), base.tiering().criticalWatermark()),
                            num(tiering.get("backpressure-timeout-ms"), base.tiering().backpressureTimeoutMillis()),
                            num(tiering.get("drain-timeout-ms"), base.tiering().drainTimeoutMillis())),
                    new Gc(num(gc.get("batch-size"), base.gc().batchSize()),
                            num(gc.get("worker-count"), base.gc().workerCount()),
                            num(gc.get("max-memory"), base.gc().maxMemoryBytes())));
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public String describe() {
        return String.format(
                "server.host=%s server.port=%d shard-count=%d flush-workers=%d "
                        + "migration-workers=%d memory.limit=%d wal.fsync=%s "
                        + "cache.block-size=%d hotkey-window=%d hotkey-threshold=%d "
                        + "watermarks=%.2f/%.2f/%.2f backpressure-timeout=%d drain-timeout=%d "
                        + "gc.batch-size=%d gc.worker-count=%d gc.max-memory=%d",
                server().host(), server().port(), workers().shardCount(),
                workers().flushWorkers(), workers().migrationWorkers(),
                memory().maxBytes(), wal().fsyncPolicy(), cache().blockCacheCapacity(),
                cache().hotKeyWindowMillis(), cache().hotKeyThreshold(),
                tiering().lowWatermark(), tiering().highWatermark(),
                tiering().criticalWatermark(), tiering().backpressureTimeoutMillis(),
                tiering().drainTimeoutMillis(), gc().batchSize(), gc().workerCount(),
                gc().maxMemoryBytes());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static long num(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static double dbl(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
