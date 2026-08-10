package io.tieringkv;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cache.CacheConfig;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TierMigration;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.MemTable;

/** Tiering-KV 入口：默认监听 0.0.0.0:6379。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig("0.0.0.0", 6379);
        MemoryManager memoryManager = new MemoryManager(1L << 30);
        MemTable memTable = MemTable.create(memoryManager);
        CacheConfig cacheConfig = CacheConfig.defaults();
        EvictionManager evictionManager = new EvictionManager(
                memTable,
                memoryManager,
                new LFUPolicy(cacheConfig.decayIntervalMillis()),
                TierMigration.discard());
        StorageEngine storage = new TrackingStorageEngine(memTable, evictionManager);
        TieringKvServer server = new TieringKvServer(
                config,
                new CommandEngine(CommandRegistry.createDefault(), storage));
        server.start();
        System.out.println("Tiering-KV listening on " + server.boundPort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
