package io.tieringkv.config;

import io.tieringkv.storage.wal.WALConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** 应用配置（ADR-0034 + M3-B1）：YAML 全量配置化 + 严格参数校验，启动打印脱敏生效配置。 */
public record TieringConfig(
        Server server,
        Workers workers,
        Memory memory,
        Wal wal,
        Cache cache,
        Tiering tiering,
        Gc gc,
        Txn txn,
        Storage storage,
        Vector vector) {

    public TieringConfig(
            Server server,
            Workers workers,
            Memory memory,
            Wal wal,
            Cache cache,
            Tiering tiering,
            Gc gc,
            Txn txn) {
        this(server, workers, memory, wal, cache, tiering, gc, txn,
                Storage.defaults(), Vector.defaults());
    }

    public record Server(
            String host,
            int port,
            int maxClients,
            int timeoutSeconds,
            String password,
            Tls tls) {
        public Server(String host, int port) {
            this(host, port, 10_000, 0, null, Tls.disabled());
        }

        public record Tls(
                boolean enabled,
                String certPath,
                String keyPath,
                String caPath,
                boolean requireClientAuth) {
            public static Tls disabled() {
                return new Tls(false, null, null, null, false);
            }
        }
    }

    public record Workers(int shardCount, int flushWorkers, int migrationWorkers) {
    }

    public record Memory(long maxBytes) {
    }

    public record Wal(String fsyncPolicy, long maxSegmentBytes) {
    }

    public record Cache(
            int blockCacheCapacity,
            long hotKeyWindowMillis,
            long hotKeyThreshold,
            int maxHotKeys,
            long hotKeyCacheTtlMillis,
            long evictionDecayIntervalMillis,
            int maxEvictionsPerCycle) {
        public Cache(int blockCacheCapacity, long hotKeyWindowMillis, long hotKeyThreshold) {
            this(blockCacheCapacity, hotKeyWindowMillis, hotKeyThreshold,
                    1024, 500, 60_000, 1024);
        }
    }

    public record Tiering(
            double lowWatermark,
            double highWatermark,
            double criticalWatermark,
            long backpressureTimeoutMillis,
            long drainTimeoutMillis,
            long maxEntryCount,
            long warningQueueTasks,
            long criticalQueueTasks) {
        public Tiering(
                double lowWatermark,
                double highWatermark,
                double criticalWatermark,
                long backpressureTimeoutMillis,
                long drainTimeoutMillis) {
            this(lowWatermark, highWatermark, criticalWatermark,
                    backpressureTimeoutMillis, drainTimeoutMillis,
                    1_000_000L, 5_000L, 10_000L);
        }
    }

    public record Gc(int batchSize, int workerCount, long maxMemoryBytes) {
    }

    public record Txn(long ttlSeconds, long maxDurationSeconds) {
    }

    public record Storage(String dataDir, String walDir, String coldDir, String migrationDir) {
        public static Storage defaults() {
            return ofDataDir("./data");
        }

        public static Storage ofDataDir(String dataDir) {
            String base = (dataDir == null || dataDir.isBlank()) ? "./data" : dataDir;
            Path root = Path.of(base);
            return new Storage(
                    base,
                    root.resolve("wal").toString(),
                    root.resolve("cold").toString(),
                    root.resolve("migration").toString());
        }
    }

    public record Vector(String indexDir, int maxLevel, long checkpointIntervalMillis) {
        public static Vector defaults() {
            return new Vector(Path.of("./data", "vector").toString(), 6, 60_000L);
        }
    }

    public static TieringConfig defaults() {
        return new TieringConfig(
                new Server("0.0.0.0", 6379),
                new Workers(16, 1, 2),
                new Memory(1L << 30),
                new Wal("EVERY_SEC", 64L << 20),
                new Cache(1024, 1000, 1000),
                new Tiering(0.70, 0.85, 0.95, 1000, 5000),
                new Gc(4096, 4, 64L << 20),
                new Txn(60, 300),
                Storage.defaults(),
                Vector.defaults());
    }

    /** 启动前置校验：拒绝明显非法的水位、内存、WAL 与向量层级配置。 */
    public void validate() {
        if (memory.maxBytes() <= 0) {
            throw new IllegalArgumentException("memory.limit must be positive");
        }
        if (wal.maxSegmentBytes() <= 0) {
            throw new IllegalArgumentException("wal.max-segment-bytes must be positive");
        }
        try {
            WALConfig.FsyncPolicy.valueOf(wal.fsyncPolicy().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid wal.fsync-policy: " + wal.fsyncPolicy(), e);
        }
        if (tiering.lowWatermark() <= 0
                || tiering.lowWatermark() >= tiering.highWatermark()
                || tiering.highWatermark() >= tiering.criticalWatermark()
                || tiering.criticalWatermark() > 1.0) {
            throw new IllegalArgumentException("tiering watermarks must satisfy 0 < low < high < critical <= 1.0");
        }
        if (vector.maxLevel() < 1) {
            throw new IllegalArgumentException("vector.max-level must be >= 1");
        }
        if (cache.blockCacheCapacity() < 1 || cache.maxHotKeys() < 1 || cache.maxEvictionsPerCycle() < 1) {
            throw new IllegalArgumentException("cache capacities must be >= 1");
        }
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
            Map<String, Object> serverTls = map(server.get("tls"));
            Map<String, Object> workers = map(root.get("worker"));
            Map<String, Object> memory = map(root.get("memory"));
            Map<String, Object> wal = map(root.get("wal"));
            Map<String, Object> cache = map(root.get("cache"));
            Map<String, Object> tiering = map(root.get("tiering"));
            Map<String, Object> gc = map(root.get("gc"));
            Map<String, Object> txn = map(root.get("txn"));
            Map<String, Object> storage = map(root.get("storage"));
            Map<String, Object> vector = map(root.get("vector"));

            String password = str(server.get("password"), null);
            if (password == null || password.isBlank()) {
                String envPass = System.getenv("TIERING_PASSWORD");
                if (envPass != null && !envPass.isBlank()) {
                    password = envPass;
                }
            }

            String dataDir = str(storage.get("data-dir"), base.storage().dataDir());
            Storage defaultPaths = Storage.ofDataDir(dataDir);
            Storage resolvedStorage = new Storage(
                    dataDir,
                    str(storage.get("wal-dir"), defaultPaths.walDir()),
                    str(storage.get("cold-dir"), defaultPaths.coldDir()),
                    str(storage.get("migration-dir"), defaultPaths.migrationDir()));
            Vector resolvedVector = new Vector(
                    str(vector.get("index-dir"), Path.of(dataDir, "vector").toString()),
                    num(vector.get("max-level"), base.vector().maxLevel()),
                    num(vector.get("checkpoint-interval-ms"), base.vector().checkpointIntervalMillis()));

            return new TieringConfig(
                    new Server(
                            str(server.get("host"), base.server().host()),
                            num(server.get("port"), base.server().port()),
                            num(server.get("max-clients"), base.server().maxClients()),
                            num(server.get("timeout-seconds"), base.server().timeoutSeconds()),
                            password,
                            new Server.Tls(
                                    bool(serverTls.get("enabled"), base.server().tls().enabled()),
                                    str(serverTls.get("cert-path"), base.server().tls().certPath()),
                                    str(serverTls.get("key-path"), base.server().tls().keyPath()),
                                    str(serverTls.get("ca-path"), base.server().tls().caPath()),
                                    bool(serverTls.get("require-client-auth"), base.server().tls().requireClientAuth()))),
                    new Workers(num(workers.get("shard-count"), base.workers().shardCount()),
                            num(workers.get("flush-workers"), base.workers().flushWorkers()),
                            num(workers.get("migration-workers"), base.workers().migrationWorkers())),
                    new Memory(num(memory.get("limit"), base.memory().maxBytes())),
                    new Wal(str(wal.get("fsync-policy"), base.wal().fsyncPolicy()),
                            num(wal.get("max-segment-bytes"), base.wal().maxSegmentBytes())),
                    new Cache(num(cache.get("block-size"), base.cache().blockCacheCapacity()),
                            num(cache.get("hotkey-window-ms"), base.cache().hotKeyWindowMillis()),
                            num(cache.get("hotkey-threshold"), base.cache().hotKeyThreshold()),
                            num(cache.get("max-hot-keys"), base.cache().maxHotKeys()),
                            num(cache.get("hotkey-cache-ttl-ms"), base.cache().hotKeyCacheTtlMillis()),
                            num(cache.get("eviction-decay-interval-ms"), base.cache().evictionDecayIntervalMillis()),
                            num(cache.get("max-evictions-per-cycle"), base.cache().maxEvictionsPerCycle())),
                    new Tiering(dbl(tiering.get("low-watermark"), base.tiering().lowWatermark()),
                            dbl(tiering.get("high-watermark"), base.tiering().highWatermark()),
                            dbl(tiering.get("critical-watermark"), base.tiering().criticalWatermark()),
                            num(tiering.get("backpressure-timeout-ms"), base.tiering().backpressureTimeoutMillis()),
                            num(tiering.get("drain-timeout-ms"), base.tiering().drainTimeoutMillis()),
                            num(tiering.get("max-entry-count"), base.tiering().maxEntryCount()),
                            num(tiering.get("warning-queue-tasks"), base.tiering().warningQueueTasks()),
                            num(tiering.get("critical-queue-tasks"), base.tiering().criticalQueueTasks())),
                    new Gc(num(gc.get("batch-size"), base.gc().batchSize()),
                            num(gc.get("worker-count"), base.gc().workerCount()),
                            num(gc.get("max-memory"), base.gc().maxMemoryBytes())),
                    new Txn(num(txn.get("ttl-seconds"), base.txn().ttlSeconds()),
                            num(txn.get("max-duration-seconds"), base.txn().maxDurationSeconds())),
                    resolvedStorage,
                    resolvedVector);
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public String describe() {
        return String.format(
                "server.host=%s server.port=%d server.max-clients=%d server.timeout-seconds=%d "
                        + "server.auth=%s server.tls=%s storage.data-dir=%s vector.index-dir=%s "
                        + "shard-count=%d flush-workers=%d migration-workers=%d "
                        + "memory.limit=%d wal.fsync=%s cache.block-size=%d "
                        + "hotkey-window=%d hotkey-threshold=%d watermarks=%.2f/%.2f/%.2f "
                        + "backpressure-timeout=%d drain-timeout=%d gc.batch-size=%d "
                        + "gc.worker-count=%d gc.max-memory=%d txn.ttl-seconds=%d "
                        + "txn.max-duration-seconds=%d",
                server().host(), server().port(), server().maxClients(), server().timeoutSeconds(),
                (server().password() != null && !server().password().isBlank()) ? "enabled" : "disabled",
                server().tls().enabled(), storage().dataDir(), vector().indexDir(),
                workers().shardCount(), workers().flushWorkers(), workers().migrationWorkers(),
                memory().maxBytes(), wal().fsyncPolicy(), cache().blockCacheCapacity(),
                cache().hotKeyWindowMillis(), cache().hotKeyThreshold(),
                tiering().lowWatermark(), tiering().highWatermark(), tiering().criticalWatermark(),
                tiering().backpressureTimeoutMillis(), tiering().drainTimeoutMillis(),
                gc().batchSize(), gc().workerCount(), gc().maxMemoryBytes(),
                txn().ttlSeconds(), txn().maxDurationSeconds());
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

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }
}
