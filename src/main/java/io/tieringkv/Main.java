package io.tieringkv;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cache.CacheConfig;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.MemTable;

import java.nio.file.Path;

/** Tiering-KV 入口：默认监听 0.0.0.0:6379。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig("0.0.0.0", 6379);
        MemoryManager memoryManager = new MemoryManager(1L << 30);
        MemTable memTable = MemTable.create(memoryManager);
        WALManager walManager = new WALManager(WALConfig.defaults(Path.of("./data/wal")));
        RecoveryManager.RecoveryStats stats = walManager.recover(memTable);
        System.out.printf("WAL recovery: scanned=%d applied=%d segments=%d%n",
                stats.recordsScanned(), stats.recordsApplied(), stats.segmentsReplayed());
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(Path.of("./data/cold")));
        TieringController tiering = new TieringController(
                TieringController.Config.defaults(Path.of("./data/migration")),
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
        StorageEngine storage = new TrackingStorageEngine(
                new TieringStorageEngine(
                        new WALStorageEngine(walManager, memTable), tiering),
                evictionManager);
        TieringKvServer server = new TieringKvServer(
                config,
                new CommandEngine(CommandRegistry.createDefault(), storage));
        server.start();
        System.out.println("Tiering-KV listening on " + server.boundPort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
