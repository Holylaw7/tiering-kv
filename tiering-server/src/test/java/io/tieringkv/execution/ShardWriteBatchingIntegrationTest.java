package io.tieringkv.execution;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.config.TieringConfig;
import io.tieringkv.lifecycle.PhysicalSnapshotCoordinator;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.observability.ManagementHttpServer;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.VectorIndexSyncStorageEngine;
import io.tieringkv.storage.cache.CacheConfig;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.SampledLfuPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringReadStorageEngine;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v6.5.0 Phase 2 端到端集成测试：
 * 验证 ShardWorker 连续写批聚合、混合读写严格 Read-Your-Own-Writes 线性一致性、
 * OOM 内存压力下拒绝 SET 但放行 DEL、配置加载校验与 INFO/Prometheus 指标导出。
 */
class ShardWriteBatchingIntegrationTest {

    @TempDir
    Path tempDir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static RespCommand cmd(String name, String... args) {
        List<byte[]> list = new ArrayList<>(args.length);
        for (String a : args) {
            list.add(b(a));
        }
        return new RespCommand(name, list);
    }

    @Test
    void interleavedWritesAndReadsPreserveStrictLinearizabilityAndCoalesceBatches() throws Exception {
        MemoryManager memory = new MemoryManager(128L << 20);
        MemTable memTable = MemTable.create(memory);
        WALConfig walConfig = new WALConfig(tempDir.resolve("wal"), 4L << 20,
                WALConfig.FsyncPolicy.EVERY_SEC, 4, WALConfig.IoMode.NIO, false);
        AtomicBoolean oom = new AtomicBoolean(false);

        try (WALManager wal = new WALManager(walConfig);
             ColdStorageEngine cold = new ColdStorageEngine(
                     ColdStorageEngine.Config.defaults(tempDir.resolve("cold")),
                     new BlockCache(new CachePolicy(64), new MemoryPool()),
                     new IOStatistics(), true);
             TieringController tiering = new TieringController(
                     TieringController.Config.defaults(tempDir.resolve("mig")),
                     memory, memTable, wal, cold);
             KeyShardExecutor executor = new KeyShardExecutor(4, "test-batch-shard", 64)) {

            WALStorageEngine walStorage = new WALStorageEngine(wal, memTable);
            HotKeyPolicy hkPolicy = new HotKeyPolicy(1000, 1000, 256, 500);
            HotKeyReadCache hkCache = new HotKeyReadCache(new HotKeyDetector(hkPolicy), hkPolicy, walStorage);
            EvictionManager eviction = new EvictionManager(
                    memTable, memory,
                    new SampledLfuPolicy(memTable, 60_000, 16, System::currentTimeMillis, null),
                    new ColdMigration(cold), wal, tiering.migrationScheduler(),
                    System::currentTimeMillis, CacheConfig.defaults().maxEvictionsPerCycle());
            StorageEngine stacked = new VectorIndexSyncStorageEngine(
                    new TieringReadStorageEngine(
                            new TrackingStorageEngine(
                                    new TieringStorageEngine(
                                            new HotKeyStorageEngine(walStorage, hkCache), tiering),
                                    eviction),
                            cold),
                    new VectorIndexStore(4));

            CommandEngine engine = new CommandEngine(
                    CommandRegistry.createDefault(), stacked, executor, oom::get, null, null);

            // 1) 同分片高密度连续 SET 管线：验证批聚合真实生效
            List<CompletableFuture<RespValue>> writeFutures = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                CompletableFuture<RespValue> cf = new CompletableFuture<>();
                engine.executeAsync(cmd("set", "batch-key-" + i, "val-" + i), (resp, err) -> {
                    if (err != null) {
                        cf.completeExceptionally(err);
                    } else {
                        cf.complete(resp);
                    }
                });
                writeFutures.add(cf);
            }
            for (CompletableFuture<RespValue> cf : writeFutures) {
                assertThat(cf.get(10, TimeUnit.SECONDS)).isEqualTo(new RespSimpleString("OK"));
            }
            assertThat(wal.groupCommitRecords()).isEqualTo(400);
            assertThat(wal.groupCommitBatches())
                    .as("高密度管线写必须发生批聚合（批次数 < 记录数）")
                    .isLessThan(400);

            // 2) 同键交错 SET -> GET -> SET(PX) -> GET -> DEL -> GET -> DEL：严格 Read-Your-Own-Writes
            List<CompletableFuture<RespValue>> ryo = new ArrayList<>();
            RespCommand[] seq = {
                    cmd("set", "hot-k", "v1"),
                    cmd("get", "hot-k"),
                    cmd("set", "hot-k", "v2", "PX", "60000"),
                    cmd("get", "hot-k"),
                    cmd("del", "hot-k"),
                    cmd("get", "hot-k"),
                    cmd("del", "hot-k")
            };
            for (RespCommand c : seq) {
                CompletableFuture<RespValue> cf = new CompletableFuture<>();
                engine.executeAsync(c, (resp, err) -> {
                    if (err != null) {
                        cf.completeExceptionally(err);
                    } else {
                        cf.complete(resp);
                    }
                });
                ryo.add(cf);
            }
            assertThat(ryo.get(0).get(5, TimeUnit.SECONDS)).isEqualTo(new RespSimpleString("OK"));
            assertThat(((RespBulkString) ryo.get(1).get(5, TimeUnit.SECONDS)).bytes()).isEqualTo(b("v1"));
            assertThat(ryo.get(2).get(5, TimeUnit.SECONDS)).isEqualTo(new RespSimpleString("OK"));
            assertThat(((RespBulkString) ryo.get(3).get(5, TimeUnit.SECONDS)).bytes()).isEqualTo(b("v2"));
            assertThat(ryo.get(4).get(5, TimeUnit.SECONDS)).isEqualTo(new RespInteger(1));
            assertThat(ryo.get(5).get(5, TimeUnit.SECONDS)).isInstanceOf(RespNull.class);
            assertThat(ryo.get(6).get(5, TimeUnit.SECONDS)).isEqualTo(new RespInteger(0));

            // 3) OOM 压力下同批混杂 SET + DEL：SET 被拒绝，DEL 仍必须放行
            oom.set(true);
            List<CompletableFuture<RespValue>> oomFutures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                CompletableFuture<RespValue> setCf = new CompletableFuture<>();
                engine.executeAsync(cmd("set", "oom-k-" + i, "v"), (r, e) -> setCf.complete(r));
                oomFutures.add(setCf);
                CompletableFuture<RespValue> delCf = new CompletableFuture<>();
                engine.executeAsync(cmd("del", "batch-key-" + i), (r, e) -> delCf.complete(r));
                oomFutures.add(delCf);
            }
            for (int i = 0; i < 10; i++) {
                assertThat(oomFutures.get(i * 2).get(5, TimeUnit.SECONDS)).isInstanceOf(RespError.class);
                assertThat(oomFutures.get(i * 2 + 1).get(5, TimeUnit.SECONDS)).isEqualTo(new RespInteger(1));
            }

            // 4) 验证 INFO persistence 与 Prometheus /metrics 真实暴露 WAL 新指标
            try (PhysicalSnapshotCoordinator coord = new PhysicalSnapshotCoordinator(wal, memTable, cold);
                 ManagementHttpServer mgmt = new ManagementHttpServer(
                         "127.0.0.1", 0, new MetricsRegistry(), tiering.metrics(),
                         null, null, null, wal)) {
                ServerInfoProvider info = new ServerInfoProvider(
                        new MetricsRegistry(), memory, stacked::size, 6379, "6.5.0",
                        tiering.metrics(), null, null, coord, wal);
                String persistence = info.sections().get("persistence").get();
                assertThat(persistence)
                        .contains("wal_shards:4")
                        .contains("wal_io_backend:nio")
                        .contains("wal_write_records:");
                String prom = mgmt.renderPrometheus();
                assertThat(prom)
                        .contains("tieringkv_wal_shards 4")
                        .contains("tieringkv_wal_write_batches_total ")
                        .contains("tieringkv_wal_io_backend{backend=\"nio\"");
            }
        }
    }

    @Test
    void tieringConfigParsesAndValidatesShardedWalOptions() throws Exception {
        Path yaml = tempDir.resolve("application.yaml");
        Files.writeString(yaml, """
                wal:
                  fsync-policy: EVERY_SEC
                  max-segment-bytes: 33554432
                  shards: 16
                  io-mode: auto
                  preallocate: true
                  batch-max-entries: 64
                """);
        TieringConfig loaded = TieringConfig.load(yaml);
        loaded.validate();
        assertThat(loaded.wal().shardCount()).isEqualTo(16);
        assertThat(loaded.wal().ioMode()).isEqualTo("auto");
        assertThat(loaded.wal().preallocate()).isTrue();
        assertThat(loaded.wal().batchMaxEntries()).isEqualTo(64);

        TieringConfig badShards = new TieringConfig(
                loaded.server(), loaded.workers(), loaded.memory(),
                new TieringConfig.Wal("EVERY_SEC", 1 << 20, 12, "auto", true, 64),
                loaded.cache(), loaded.tiering(), loaded.gc(), loaded.txn(),
                loaded.storage(), loaded.vector(), loaded.observability());
        assertThatThrownBy(badShards::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
