package io.tieringkv.command;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.lifecycle.PhysicalSnapshotCoordinator;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.VectorIndexSyncStorageEngine;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.tiering.TieringReadStorageEngine;
import io.tieringkv.storage.wal.CheckpointManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** N1-B：BGSAVE / LASTSAVE 并发互斥、INFO # Persistence 暴露与全路径落盘恢复测试。 */
class BgsaveAndLastSaveCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsConcurrentBgsaveUpdatesLastSaveAndExposesPersistenceInfo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PhysicalSnapshotCoordinator coordinator = new PhysicalSnapshotCoordinator(() -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("timeout waiting test latch");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        });
        try {
            MemoryManager mm = new MemoryManager(1024 * 1024);
            ServerInfoProvider info = new ServerInfoProvider(
                    new MetricsRegistry(), mm, () -> 0L, 6379, "5.1.0",
                    null, null, null, coordinator);
            AclPolicy acl = new AclPolicy();
            CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                    info::render, info.sections(), null, null, null,
                    acl, RuntimeConfigBridge.standaloneDefaults(), null, coordinator);
            CommandEngine engine = new CommandEngine(registry, MemTable.create(), null, null, acl, null);

            assertThat(engine.execute(cmd("LASTSAVE"))).isEqualTo(new RespInteger(0));
            String initialInfo = infoText(engine.execute(cmd("INFO", "persistence")));
            assertThat(initialInfo)
                    .contains("# Persistence\r\n")
                    .contains("rdb_bgsave_in_progress:0\r\n")
                    .contains("rdb_last_save_time:0\r\n")
                    .contains("rdb_last_bgsave_status:ok\r\n")
                    .contains("rdb_last_bgsave_time_sec:-1\r\n");

            assertThat(engine.execute(cmd("BGSAVE")))
                    .isEqualTo(new RespSimpleString("Background saving started"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            String runningInfo = infoText(engine.execute(cmd("INFO", "persistence")));
            assertThat(runningInfo).contains("rdb_bgsave_in_progress:1\r\n");

            RespValue dup = engine.execute(cmd("BGSAVE"));
            assertThat(dup).isInstanceOf(RespError.class);
            assertThat(((RespError) dup).message()).contains("Background save already in progress");

            release.countDown();
            awaitBgsaveDone(coordinator);

            long ts = ((RespInteger) engine.execute(cmd("LASTSAVE"))).value();
            assertThat(ts).isGreaterThan(0L);
            String finishedInfo = infoText(engine.execute(cmd("INFO", "persistence")));
            assertThat(finishedInfo)
                    .contains("rdb_bgsave_in_progress:0\r\n")
                    .contains("rdb_last_save_time:" + ts + "\r\n")
                    .contains("rdb_last_bgsave_status:ok\r\n");
        } finally {
            release.countDown();
            coordinator.close();
        }
    }

    @Test
    void bgsaveFailureSetsErrorStatusAndPreservesPreviousLastSave() throws Exception {
        PhysicalSnapshotCoordinator coordinator = new PhysicalSnapshotCoordinator(() -> {
            throw new IOException("disk read-only");
        });
        try {
            ServerInfoProvider info = new ServerInfoProvider(
                    new MetricsRegistry(), new MemoryManager(1024 * 1024), () -> 0L, 6379, "5.1.0",
                    null, null, null, coordinator);
            CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                    info::render, info.sections(), null, null, null,
                    new AclPolicy(), RuntimeConfigBridge.standaloneDefaults(), null, coordinator);
            CommandEngine engine = new CommandEngine(registry, MemTable.create());

            assertThat(engine.execute(cmd("BGSAVE")))
                    .isEqualTo(new RespSimpleString("Background saving started"));
            awaitBgsaveDone(coordinator);

            assertThat(engine.execute(cmd("LASTSAVE"))).isEqualTo(new RespInteger(0));
            String errInfo = infoText(engine.execute(cmd("INFO", "persistence")));
            assertThat(errInfo)
                    .contains("rdb_last_bgsave_status:err\r\n")
                    .contains("rdb_last_bgsave_error:disk read-only\r\n");
        } finally {
            coordinator.close();
        }
    }

    @Test
    void bgsaveFlushesWalColdAndVectorsSoRestartRecoversAllState() throws Exception {
        Path walDir = tempDir.resolve("wal");
        Path coldDir = tempDir.resolve("cold");
        Path vectorFile = tempDir.resolve("vector").resolve("default.vidx");

        MemoryManager mm = new MemoryManager(16 * 1024 * 1024);
        MemTable memTable = MemTable.create(mm);
        try (WALManager wal = new WALManager(new WALConfig(walDir, 1024 * 1024, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold = new ColdStorageEngine(
                     ColdStorageEngine.Config.defaults(coldDir),
                     new BlockCache(new CachePolicy(1024 * 1024), new MemoryPool()),
                     new IOStatistics(), false)) {
            VectorIndexStore vectorStore = new VectorIndexStore(4);
            VectorMetricsRegistry vecMetrics = new VectorMetricsRegistry(vectorStore);
            VectorIndexSyncStorageEngine syncStorage = new VectorIndexSyncStorageEngine(
                    new TieringReadStorageEngine(new WALStorageEngine(wal, memTable), cold),
                    vectorStore, vecMetrics);

            // 热层写入 + 冷层 pending 缓冲区写入 + 向量写入
            cold.put(KeyValueEntry.live(
                    "cold-k".getBytes(StandardCharsets.UTF_8),
                    "cold-v".getBytes(StandardCharsets.UTF_8),
                    System.currentTimeMillis(), -1L, 1L));
            assertThat(cold.pendingCount()).isEqualTo(1);

            PhysicalSnapshotCoordinator coordinator = new PhysicalSnapshotCoordinator(
                    wal, memTable, cold, vectorStore, vectorFile, vecMetrics);
            try {
                CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                        () -> "# Server\r\n", Map.of(),
                        VectorCollectionRegistry.ofDefault(vectorStore),
                        null, vecMetrics, new AclPolicy(), RuntimeConfigBridge.standaloneDefaults(),
                        null, coordinator);
                CommandEngine engine = new CommandEngine(registry, syncStorage);

                assertThat(engine.execute(cmd("SET", "hot-k", "hot-v")))
                        .isEqualTo(new RespSimpleString("OK"));
                assertThat(engine.execute(cmd("VECTOR.ADD", "doc1", "2", "1.0", "0.0")))
                        .isInstanceOf(RespSimpleString.class);

                assertThat(engine.execute(cmd("BGSAVE")))
                        .isEqualTo(new RespSimpleString("Background saving started"));
                awaitBgsaveDone(coordinator);

                assertThat(wal.loadCheckpoint()).isNotNull();
                assertThat(cold.pendingCount()).isZero();
                assertThat(cold.tablesSnapshot()).isNotEmpty();
                assertThat(Files.exists(vectorFile)).isTrue();
                assertThat(vecMetrics.snapshot().checkpoints()).isEqualTo(1L);
            } finally {
                coordinator.close();
            }
        }

        // 重启恢复校验：仅通过磁盘文件恢复 MemTable/Checkpoint + Cold SSTables + VectorIndexFile
        MemTable restoredMem = MemTable.create(new MemoryManager(16 * 1024 * 1024));
        try (WALManager wal2 = new WALManager(new WALConfig(walDir, 1024 * 1024, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold2 = new ColdStorageEngine(
                     ColdStorageEngine.Config.defaults(coldDir),
                     new BlockCache(new CachePolicy(1024 * 1024), new MemoryPool()),
                     new IOStatistics(), false)) {
            CheckpointManager.Checkpoint cp = wal2.loadCheckpoint();
            assertThat(cp).isNotNull();
            CheckpointManager.restore(restoredMem, cp);
            wal2.recoverFrom(restoredMem, cp.segmentSequence(), cp.offset());

            assertThat(new String(restoredMem.get("hot-k".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8)).isEqualTo("hot-v");
            assertThat(new String(cold2.get("cold-k".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8)).isEqualTo("cold-v");

            VectorIndexStore restoredVec = VectorIndexStore.load(vectorFile);
            assertThat(restoredVec.size()).isEqualTo(1);
        }
    }

    @Test
    void enforcesAdminAclForBgsaveAndLastSave() throws Exception {
        PhysicalSnapshotCoordinator coordinator = new PhysicalSnapshotCoordinator(() -> {});
        try {
            AclPolicy acl = new AclPolicy();
            acl.setUser("reader", List.of("on", "nopass", "+@read", "-@admin", "~*"));
            CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                    () -> "# Server\r\n", Map.of(), null, null, null,
                    acl, RuntimeConfigBridge.standaloneDefaults(), null, coordinator);
            CommandEngine engine = new CommandEngine(registry, MemTable.create(), null, null, acl, null);

            ConnectionContext ctx = ConnectionContext.enter();
            try {
                ctx.setAuthenticatedUser("reader");
                RespValue denied = engine.execute(cmd("BGSAVE"));
                assertThat(denied).isInstanceOf(RespError.class);
                assertThat(((RespError) denied).message()).contains("NOPERM");
            } finally {
                ConnectionContext.exit();
            }
        } finally {
            coordinator.close();
        }
    }

    private static void awaitBgsaveDone(PhysicalSnapshotCoordinator coordinator) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (coordinator.status().inProgress() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(coordinator.status().inProgress()).isFalse();
    }

    private static String infoText(RespValue value) {
        return new String(((RespBulkString) value).bytes(), StandardCharsets.UTF_8);
    }

    private static RespCommand cmd(String... parts) {
        List<byte[]> args = new java.util.ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i].getBytes(StandardCharsets.UTF_8));
        }
        return new RespCommand(parts[0], args);
    }
}
