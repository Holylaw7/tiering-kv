package io.tieringkv;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RuntimeConfigBridge;
import io.tieringkv.config.TieringConfig;
import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.lifecycle.PhysicalSnapshotCoordinator;
import io.tieringkv.lifecycle.ShutdownManager;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.observability.ManagementHttpServer;
import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.observability.ObservabilityRegistry;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.observability.SlowLog;
import io.tieringkv.observability.SstableInfoRenderer;
import io.tieringkv.observability.ReplicationMetricsRegistry;
import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.VectorIndexSyncStorageEngine;
import io.tieringkv.storage.cache.ARCPolicy;
import io.tieringkv.storage.cache.CacheConfig;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.EvictionPolicy;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.SampledLfuPolicy;
import io.tieringkv.storage.cache.SegmentLFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringReadStorageEngine;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.tiering.WatermarkManager;
import io.tieringkv.storage.wal.CheckpointManager;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.MemTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tiering-KV 入口：默认监听 0.0.0.0:6379。 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        String mode = cli.getOrDefault("mode", "standalone");
        if (!"standalone".equals(mode)) {
            for (RuntimeModeLauncher launcher : java.util.ServiceLoader.load(RuntimeModeLauncher.class)) {
                if (launcher.supports(mode)) {
                    launcher.launch(mode, args);
                    return;
                }
            }
            throw new IllegalArgumentException("unsupported --mode: " + mode
                    + " (expected standalone | cluster-node | gateway)");
        }
        Path configPath = Path.of(cli.getOrDefault("config", "config/application.yaml"));
        TieringConfig config = TieringConfig.load(configPath);
        config.validate();
        log.info("effective configuration: {}", config.describe());
        TieringConfig.Server srv = config.server();
        ServerConfig.TlsConfig tlsConfig = srv.tls() != null && srv.tls().enabled()
                ? new ServerConfig.TlsConfig(
                        true,
                        srv.tls().certPath() == null ? null : Path.of(srv.tls().certPath()),
                        srv.tls().keyPath() == null ? null : Path.of(srv.tls().keyPath()),
                        srv.tls().caPath() == null ? null : Path.of(srv.tls().caPath()),
                        srv.tls().requireClientAuth())
                : ServerConfig.TlsConfig.disabled();
        ServerConfig serverConfig = new ServerConfig(
                srv.host(), srv.port(), srv.maxClients(), srv.timeoutSeconds(), srv.password(), tlsConfig);
        MemoryManager memoryManager = new MemoryManager(config.memory().maxBytes());
        MemTable memTable = MemTable.create(memoryManager);
        WALConfig walConfig = new WALConfig(
                Path.of(config.storage().walDir()),
                config.wal().maxSegmentBytes(),
                WALConfig.FsyncPolicy.valueOf(config.wal().fsyncPolicy().trim().toUpperCase(java.util.Locale.ROOT)),
                config.wal().shardCount(),
                WALConfig.IoMode.valueOf(config.wal().ioMode().trim().toUpperCase(java.util.Locale.ROOT)),
                config.wal().preallocate());
        WALManager walManager = new WALManager(walConfig);
        CheckpointManager.Checkpoint checkpoint = walManager.loadCheckpoint();
        RecoveryManager.RecoveryStats stats;
        if (checkpoint != null) {
            CheckpointManager.restore(memTable, checkpoint);
            stats = walManager.recoverFrom(memTable, checkpoint);
        } else {
            stats = walManager.recover(memTable);
        }
        log.info("WAL recovery: shards={} backend={} preallocate={} scanned={} applied={} segments={}",
                walManager.shardCount(), walManager.activeIoBackend(), walManager.preallocateEnabled(),
                stats.recordsScanned(), stats.recordsApplied(), stats.segmentsReplayed());
        MemoryPool memoryPool = new MemoryPool();
        BlockCache blockCache = new BlockCache(
                new CachePolicy(config.cache().blockCacheCapacity()), memoryPool);
        IOStatistics ioStats = new IOStatistics();
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(Path.of(config.storage().coldDir())),
                blockCache, ioStats, true);
        boolean coldReconciled = cold.reconcileClearEpoch(walManager.committedClearEpoch());
        walManager.advanceEpochFloor(cold.clearEpoch());
        if (coldReconciled) {
            log.info("Cold storage reconciled to WAL clearEpoch={}", cold.clearEpoch());
        }
        WatermarkManager.Config watermarks = new WatermarkManager.Config(
                config.tiering().lowWatermark(), config.tiering().highWatermark(),
                config.tiering().criticalWatermark(),
                config.tiering().maxEntryCount(),
                config.tiering().warningQueueTasks(),
                config.tiering().criticalQueueTasks());
        TieringController tiering = new TieringController(
                new TieringController.Config(
                        watermarks,
                        config.workers().flushWorkers(),
                        config.workers().migrationWorkers(),
                        3, 0, config.tiering().backpressureTimeoutMillis(),
                        Path.of(config.storage().migrationDir())),
                memoryManager, memTable, walManager, cold);
        if (stats.clearedByBarrier() || coldReconciled) {
            tiering.migrationScheduler().clearLog();
        }
        tiering.recover();
        CacheConfig cacheConfig = new CacheConfig(
                config.cache().evictionDecayIntervalMillis(),
                CacheConfig.defaults().arcCapacity(),
                config.cache().maxEvictionsPerCycle());
        EvictionPolicy evictionPolicy = createEvictionPolicy(
                config.cache(), memTable, tiering.migrationScheduler(), cacheConfig);
        EvictionManager evictionManager = new EvictionManager(
                memTable,
                memoryManager,
                evictionPolicy,
                new ColdMigration(cold),
                walManager,
                tiering.migrationScheduler(),
                System::currentTimeMillis,
                cacheConfig.maxEvictionsPerCycle());
        WALStorageEngine walStorage = new WALStorageEngine(walManager, memTable);
        HotKeyPolicy hotKeyPolicy = new HotKeyPolicy(
                config.cache().hotKeyWindowMillis(),
                config.cache().hotKeyThreshold(),
                config.cache().maxHotKeys(),
                config.cache().hotKeyCacheTtlMillis());
        HotKeyReadCache hotKeyCache = new HotKeyReadCache(
                new HotKeyDetector(hotKeyPolicy), hotKeyPolicy, walStorage);
        StorageEngine storage = new TrackingStorageEngine(
                new TieringStorageEngine(
                        new HotKeyStorageEngine(walStorage, hotKeyCache), tiering),
                evictionManager);
        int shards = Math.min(config.workers().shardCount(),
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        KeyShardExecutor executor = new KeyShardExecutor(shards, "command-shard", config.wal().batchMaxEntries());
        MetricsRegistry metrics = new MetricsRegistry();
        // M3-B1 + Wave 3C K1：向量索引加载 checkpoint（若本次恢复触发了 CLEAR_ALL 屏障，跳过陈旧快照由存储全量重建）
        Path vectorDir = Path.of(config.vector().indexDir());
        Path vectorCheckpointFile = vectorDir.resolve("default.vidx");
        VectorIndexStore vectorStore = (stats.clearedByBarrier() || coldReconciled)
                ? new VectorIndexStore(config.vector().maxLevel())
                : loadOrCreateVectorStore(vectorCheckpointFile, config.vector().maxLevel());
        ObservabilityRegistry observability = new ObservabilityRegistry(
                new VectorMetricsRegistry(vectorStore),
                new ReplicationMetricsRegistry(),
                new MultiModelMetricsRegistry(),
                new BackupMetricsRegistry());
        StorageEngine tieredStorage = new TieringReadStorageEngine(storage, cold);
        VectorIndexSyncStorageEngine syncStorage = new VectorIndexSyncStorageEngine(
                tieredStorage, vectorStore, observability.vector());
        int rehydrated = syncStorage.rehydrateFromStorage();
        if (rehydrated > 0) {
            log.info("Vector index rehydrated: entries={} total={}", rehydrated, vectorStore.size());
        }
        java.util.concurrent.ScheduledExecutorService vectorCheckpointer = startVectorCheckpointer(
                vectorStore, vectorDir, vectorCheckpointFile, config.vector().checkpointIntervalMillis());
        PhysicalSnapshotCoordinator snapshotCoordinator = new PhysicalSnapshotCoordinator(
                walManager, memTable, cold, vectorStore, vectorCheckpointFile, observability.vector());
        // 可观测性收敛（ADR-0355）：INFO 输出 Redis 兼容标准 section，
        // 供 Web 控制台解析真实运行态
        ServerInfoProvider serverInfo = new ServerInfoProvider(
                metrics, memoryManager, syncStorage::size,
                config.server().port(), ServerVersion.current(),
                tiering.metrics(), blockCache.statistics()::snapshot,
                null, snapshotCoordinator, walManager); // F3：INFO 不触发刷新、不等待刷新，只读最近一次快照
        Map<String, Supplier<String>> infoSections =
                new HashMap<>(observability.infoSections());
        infoSections.putAll(serverInfo.sections());
        // TD-099：SSTable 清单按需读取，不进入全量 INFO
        infoSections.put("sstables", () ->
                SstableInfoRenderer.render(cold.tablesSnapshot()));
        AclPolicy aclPolicy = new AclPolicy();
        if (serverConfig.password() != null && !serverConfig.password().isBlank()) {
            aclPolicy.configureRequirePass(serverConfig.password());
        }
        TieringKvServer.RuntimeOptions serverOptions = new TieringKvServer.RuntimeOptions(
                serverConfig.maxClients(), serverConfig.idleTimeoutSeconds());
        RuntimeConfigBridge runtimeConfig = RuntimeConfigBridge.builder()
                .maxMemory(memoryManager::maxBytes, memoryManager::setMaxBytes)
                .appendFsync(walManager::fsyncPolicy, walManager::setFsyncPolicy)
                .maxClients(serverOptions::maxClients, serverOptions::setMaxClients)
                .timeout(serverOptions::idleTimeoutSeconds, serverOptions::setIdleTimeoutSeconds)
                .requirePass(aclPolicy::requiresAuthForAnonymous, aclPolicy::configureRequirePass)
                .build();
        SlowLog slowLog = new SlowLog(
                config.observability().slowLogThresholdMicros(),
                config.observability().slowLogMaxLen());
        VectorCollectionRegistry vectorRegistry = VectorCollectionRegistry.ofDefault(vectorStore);
        vectorRegistry.configureCheckpoint(vectorDir);
        CommandEngine commandEngine = new CommandEngine(
                CommandRegistry.createDefaultWithRuntime(
                        serverInfo::render, Map.copyOf(infoSections),
                        vectorRegistry,
                        observability.multimodel(), observability.vector(),
                        aclPolicy, runtimeConfig, slowLog, snapshotCoordinator),
                syncStorage, executor, memoryManager::isOverLimit, aclPolicy, slowLog);
        // 必须复用同一个 MetricsRegistry：否则 INFO 读取的实例与服务端
        // 实际埋点的实例不是同一个，qps/连接数会恒为 0（TD-082）
        TieringKvServer server = new TieringKvServer(
                serverConfig,
                commandEngine,
                metrics,
                serverOptions);
        ManagementHttpServer managementHttp = null;
        if (config.observability().managementPort() > 0) {
            managementHttp = new ManagementHttpServer(
                    srv.host(),
                    config.observability().managementPort(),
                    metrics,
                    tiering.metrics(),
                    blockCache.statistics()::snapshot,
                    observability,
                    slowLog,
                    walManager);
            managementHttp.start();
            log.info("Tiering-KV management HTTP listening on {} (/healthz, /readyz, /metrics)",
                    managementHttp.boundPort());
        }
        AutoCloseable vectorCloser = () -> {
            if (vectorCheckpointer != null) {
                vectorCheckpointer.shutdownNow();
            }
            if (vectorStore.size() > 0 || java.nio.file.Files.exists(vectorCheckpointFile)) {
                java.nio.file.Files.createDirectories(vectorDir);
                vectorStore.checkpoint(vectorCheckpointFile);
            }
        };
        ShutdownManager shutdownManager = new ShutdownManager(
                server, metrics, walManager, memTable, config.tiering().drainTimeoutMillis(),
                managementHttp, snapshotCoordinator, vectorCloser, executor, tiering, cold, walManager);
        server.start();
        log.info("Tiering-KV listening on {}", server.boundPort());
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownManager::shutdown));
        server.awaitTermination();
    }

    static EvictionPolicy createEvictionPolicy(
            TieringConfig.Cache cacheCfg,
            MemTable memTable,
            io.tieringkv.storage.tiering.MigrationScheduler migrationScheduler,
            CacheConfig cacheConfig) {
        String policy = cacheCfg.evictionPolicy() == null
                ? "sampled-lfu"
                : cacheCfg.evictionPolicy().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (policy) {
            case "lfu" -> new LFUPolicy(cacheConfig.decayIntervalMillis());
            case "segment-lfu" -> new SegmentLFUPolicy();
            case "arc" -> new ARCPolicy(cacheConfig.arcCapacity());
            default -> new SampledLfuPolicy(
                    memTable,
                    cacheConfig.decayIntervalMillis(),
                    cacheCfg.evictionSamples(),
                    System::currentTimeMillis,
                    migrationScheduler == null ? null : key -> !migrationScheduler.contains(key));
        };
    }

    static VectorIndexStore loadOrCreateVectorStore(Path checkpointFile, int maxLevel) {
        if (checkpointFile != null && java.nio.file.Files.exists(checkpointFile)) {
            try {
                VectorIndexStore loaded = VectorIndexStore.load(checkpointFile);
                log.info("Vector checkpoint loaded: entries={} file={}", loaded.size(), checkpointFile);
                return loaded;
            } catch (Exception ex) {
                log.warn("Vector checkpoint load failed ({}), rebuilding from storage: {}",
                        checkpointFile, ex.getMessage());
            }
        }
        return new VectorIndexStore(maxLevel);
    }

    private static java.util.concurrent.ScheduledExecutorService startVectorCheckpointer(
            VectorIndexStore store, Path dir, Path file, long intervalMillis) {
        if (intervalMillis <= 0) {
            return null;
        }
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "vector-checkpoint");
                    t.setDaemon(true);
                    return t;
                });
        scheduler.scheduleWithFixedDelay(() -> {
            if (store.size() <= 0) {
                return;
            }
            try {
                java.nio.file.Files.createDirectories(dir);
                store.checkpoint(file);
            } catch (Exception ignored) {
                // 下周期或优雅停机继续重试
            }
        }, intervalMillis, intervalMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        return scheduler;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        if (args == null) {
            return options;
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }
}