package io.tieringkv;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.TieringConfig;
import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.lifecycle.ShutdownManager;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cache.CacheConfig;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.tiering.WatermarkManager;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;

import java.nio.file.Path;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.MemTable;

/** Tiering-KV 入口：默认监听 0.0.0.0:6379。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        TieringConfig config = TieringConfig.load(Path.of("config/application.yaml"));
        System.out.println("effective configuration: " + config.describe());
        ServerConfig serverConfig = new ServerConfig(config.server().host(), config.server().port());
        MemoryManager memoryManager = new MemoryManager(config.memory().maxBytes());
        MemTable memTable = MemTable.create(memoryManager);
        WALManager walManager = new WALManager(new WALConfig(
                Path.of("./data/wal"), config.wal().maxSegmentBytes(),
                WALConfig.FsyncPolicy.valueOf(config.wal().fsyncPolicy())));
        RecoveryManager.RecoveryStats stats = walManager.recover(memTable);
        System.out.printf("WAL recovery: scanned=%d applied=%d segments=%d%n",
                stats.recordsScanned(), stats.recordsApplied(), stats.segmentsReplayed());
        MemoryPool memoryPool = new MemoryPool();
        BlockCache blockCache = new BlockCache(
                new CachePolicy(config.cache().blockCacheCapacity()), memoryPool);
        IOStatistics ioStats = new IOStatistics();
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(Path.of("./data/cold")),
                blockCache, ioStats, true);
        WatermarkManager.Config watermarks = new WatermarkManager.Config(
                config.tiering().lowWatermark(), config.tiering().highWatermark(),
                config.tiering().criticalWatermark(), 1_000_000, 5_000, 10_000);
        TieringController tiering = new TieringController(
                new TieringController.Config(
                        watermarks,
                        config.workers().flushWorkers(),
                        config.workers().migrationWorkers(),
                        3, 0, config.tiering().backpressureTimeoutMillis(),
                        Path.of("./data/migration")),
                memoryManager, memTable, walManager, cold);
        tiering.recover();
        CacheConfig cacheConfig = CacheConfig.defaults();
        EvictionManager evictionManager = new EvictionManager(
                memTable,
                memoryManager,
                new LFUPolicy(cacheConfig.decayIntervalMillis()),
                new ColdMigration(cold),
                walManager,
                tiering.migrationScheduler(),
                System::currentTimeMillis,
                cacheConfig.maxEvictionsPerCycle());
        WALStorageEngine walStorage = new WALStorageEngine(walManager, memTable);
        HotKeyPolicy hotKeyPolicy = HotKeyPolicy.defaults();
        HotKeyReadCache hotKeyCache = new HotKeyReadCache(
                new HotKeyDetector(hotKeyPolicy), hotKeyPolicy, walStorage);
        StorageEngine storage = new TrackingStorageEngine(
                new TieringStorageEngine(
                        new HotKeyStorageEngine(walStorage, hotKeyCache), tiering),
                evictionManager);
        int shards = Math.min(config.workers().shardCount(),
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        KeyShardExecutor executor = new KeyShardExecutor(shards, "command-shard");
        MetricsRegistry metrics = new MetricsRegistry();
        CommandEngine commandEngine = new CommandEngine(
                CommandRegistry.createDefault(metrics::infoText), storage, executor);
        TieringKvServer server = new TieringKvServer(
                serverConfig,
                commandEngine);
        ShutdownManager shutdownManager = new ShutdownManager(
                server, metrics, walManager, memTable, config.tiering().drainTimeoutMillis());
        server.start();
        System.out.println("Tiering-KV listening on " + server.boundPort());
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownManager::shutdown));
        server.awaitTermination();
    }
}
