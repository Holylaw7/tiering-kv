package io.tieringkv.storage.wal;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.WriteOp;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v6.5.0 Sharded WAL / Checkpoint v2 / 预分配零尾 / 旧版平滑迁移与重分片严密回归测试套件。
 */
class ShardedWalAndCheckpointV2Test {

    @TempDir
    Path tempDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(256L << 20));
    }

    @Test
    void shardedWalConcurrentWritesAndOnlineCheckpointsAreLinearizable() throws Exception {
        Path walDir = tempDir.resolve("sharded-concurrent");
        WALConfig cfg = new WALConfig(walDir, 512 * 1024, WALConfig.FsyncPolicy.EVERY_SEC,
                16, WALConfig.IoMode.NIO, false);
        MemTable live = MemTable.create(new MemoryManager(256L << 20));
        Map<String, String> expected = new ConcurrentHashMap<>();

        int writerThreads = 16;
        int keysPerThread = 200;
        AtomicBoolean done = new AtomicBoolean(false);

        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, live);
            ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 2);
            try {
                // 后台连续触发在线 checkpoint（模拟高并发下的频繁 BGSAVE/Flush）
                Future<?> cpFuture = pool.submit(() -> {
                    int count = 0;
                    while (!done.get() || count < 5) {
                        try {
                            wal.checkpoint(live);
                            count++;
                            Thread.sleep(5);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                // 后台并发切换 fsyncPolicy，验证与热写无死锁
                Future<?> cfgFuture = pool.submit(() -> {
                    WALConfig.FsyncPolicy[] policies = {
                            WALConfig.FsyncPolicy.EVERY_SEC, WALConfig.FsyncPolicy.NO, WALConfig.FsyncPolicy.EVERY_SEC
                    };
                    int idx = 0;
                    while (!done.get()) {
                        wal.setFsyncPolicy(policies[idx++ % policies.length]);
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });

                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> writers = new ArrayList<>();
                for (int t = 0; t < writerThreads; t++) {
                    final int tid = t;
                    writers.add(pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        List<WriteOp> batch = new ArrayList<>(16);
                        for (int i = 0; i < keysPerThread; i++) {
                            String k = "t" + tid + "-k" + i;
                            String v = "v-" + tid + "-" + i;
                            if (i % 10 == 0) {
                                engine.put(bytes(k), bytes("old"));
                                engine.delete(bytes(k));
                                expected.remove(k);
                            } else {
                                batch.add(WriteOp.put(bytes(k), bytes(v)));
                                expected.put(k, v);
                                if (batch.size() == 8) {
                                    engine.applyWriteBatch(-1, batch);
                                    batch.clear();
                                }
                            }
                        }
                        if (!batch.isEmpty()) {
                            engine.applyWriteBatch(-1, batch);
                        }
                    }));
                }
                start.countDown();
                for (Future<?> f : writers) {
                    f.get(30, TimeUnit.SECONDS);
                }
                done.set(true);
                cpFuture.get(15, TimeUnit.SECONDS);
                cfgFuture.get(15, TimeUnit.SECONDS);

                // 在最后一次 checkpoint 之后再追加一批增量写（同时验证快照 + 增量重放）
                for (int i = 0; i < 64; i++) {
                    String k = "post-cp-" + i;
                    String v = "post-val-" + i;
                    engine.put(bytes(k), bytes(v));
                    expected.put(k, v);
                }
                wal.flushAndForce();
            } finally {
                pool.shutdownNow();
            }
        }

        // 重启：加载 v2 Checkpoint 并并行重放 16 路增量 WAL
        MemTable restored = MemTable.create(new MemoryManager(256L << 20));
        try (WALManager wal2 = new WALManager(cfg)) {
            CheckpointManager.Checkpoint cp = wal2.loadCheckpoint();
            assertThat(cp).isNotNull();
            assertThat(cp.isSharded()).isTrue();
            assertThat(cp.shardPositions()).hasSize(16);
            CheckpointManager.restore(restored, cp);
            RecoveryManager.RecoveryStats stats = wal2.recoverFrom(restored, cp);
            assertThat(stats.corruptedRecordsDiscarded()).isZero();
        }

        assertThat(restored.size()).isEqualTo(expected.size());
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertThat(restored.get(bytes(entry.getKey())))
                    .as("key %s must match after sharded checkpoint + replay", entry.getKey())
                    .isEqualTo(bytes(entry.getValue()));
        }
    }

    @Test
    void checkpointV2DetectsTrailingChecksumCorruption() throws Exception {
        Path walDir = tempDir.resolve("ckpt-v2-crc");
        WALConfig cfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO, 4);
        MemTable live = newMemTable();
        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, live);
            for (int i = 0; i < 20; i++) {
                engine.put(bytes("k" + i), bytes("v" + i));
            }
            wal.checkpoint(live);
        }
        assertThat(CheckpointManager.read(walDir)).isNotNull();

        Path cpFile = walDir.resolve("checkpoint.bin");
        byte[] raw = Files.readAllBytes(cpFile);
        raw[raw.length - 12] ^= 0x55; // 破坏负载单字节
        Files.write(cpFile, raw);
        assertThat(CheckpointManager.read(walDir))
                .as("v2 checkpoint CRC 失配时必须返回 null 以回退全量 WAL 重放")
                .isNull();
    }

    @Test
    void legacySingleWalAutoMigratesToShardedWalWithoutDataLoss() throws Exception {
        Path walDir = tempDir.resolve("legacy-upgrade");
        // 先以旧版单 WAL（shardCount=1）写入数据并生成 v1 checkpoint
        WALConfig legacyCfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO);
        MemTable oldMem = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager legacyWal = new WALManager(legacyCfg)) {
            WALStorageEngine engine = new WALStorageEngine(legacyWal, oldMem);
            for (int i = 0; i < 50; i++) {
                engine.put(bytes("old-" + i), bytes("val-" + i));
            }
            legacyWal.checkpoint(oldMem);
            for (int i = 50; i < 80; i++) {
                engine.put(bytes("old-" + i), bytes("val-" + i));
            }
            engine.delete(bytes("old-5"));
        }

        // 再以新版 8 分片 WAL 启动恢复
        WALConfig shardedCfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO, 8);
        MemTable upgraded = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(shardedCfg)) {
            CheckpointManager.Checkpoint cp = wal2.loadCheckpoint();
            assertThat(cp).isNotNull();
            CheckpointManager.restore(upgraded, cp);
            wal2.recoverFrom(upgraded, cp);
            // 升级后继续写入新键
            WALStorageEngine engine2 = new WALStorageEngine(wal2, upgraded);
            engine2.put(bytes("new-1"), bytes("v-new"));
        }

        assertThat(upgraded.get(bytes("old-0"))).isEqualTo(bytes("val-0"));
        assertThat(upgraded.get(bytes("old-5"))).isNull();
        assertThat(upgraded.get(bytes("old-79"))).isEqualTo(bytes("val-79"));
        assertThat(upgraded.get(bytes("new-1"))).isEqualTo(bytes("v-new"));
        assertThat(Files.exists(walDir.resolve(ShardedRecoveryManager.META_FILE))).isTrue();

        // 再次重启验证迁移后持久状态完全可用
        MemTable restarted = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal3 = new WALManager(shardedCfg)) {
            CheckpointManager.Checkpoint cp = wal3.loadCheckpoint();
            assertThat(cp).isNotNull();
            assertThat(cp.isSharded()).isTrue();
            CheckpointManager.restore(restarted, cp);
            wal3.recoverFrom(restarted, cp);
        }
        assertThat(restarted.get(bytes("old-79"))).isEqualTo(bytes("val-79"));
        assertThat(restarted.get(bytes("new-1"))).isEqualTo(bytes("v-new"));
        assertThat(restarted.get(bytes("old-5"))).isNull();
    }

    @Test
    void reshardingBetween16And8PreservesAllLatestValuesAndTombstones() throws Exception {
        Path walDir = tempDir.resolve("reshard-test");
        WALConfig cfg16 = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO, 16);
        MemTable mem1 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal = new WALManager(cfg16)) {
            WALStorageEngine engine = new WALStorageEngine(wal, mem1);
            for (int i = 0; i < 120; i++) {
                engine.put(bytes("rk-" + i), bytes("v1-" + i));
            }
            for (int i = 0; i < 40; i++) {
                engine.put(bytes("rk-" + i), bytes("v2-" + i));
            }
            engine.delete(bytes("rk-10"));
        }

        // 变更分片数 16 -> 8
        WALConfig cfg8 = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO, 8);
        MemTable mem2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(cfg8)) {
            wal2.recover(mem2);
            WALStorageEngine engine2 = new WALStorageEngine(wal2, mem2);
            engine2.put(bytes("rk-0"), bytes("v3-0"));
        }
        assertThat(mem2.get(bytes("rk-0"))).isEqualTo(bytes("v3-0"));
        assertThat(mem2.get(bytes("rk-39"))).isEqualTo(bytes("v2-39"));
        assertThat(mem2.get(bytes("rk-100"))).isEqualTo(bytes("v1-100"));
        assertThat(mem2.get(bytes("rk-10"))).isNull();

        // 再变更 8 -> 16
        MemTable mem3 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal3 = new WALManager(cfg16)) {
            CheckpointManager.Checkpoint cp = wal3.loadCheckpoint();
            if (cp != null) {
                CheckpointManager.restore(mem3, cp);
                wal3.recoverFrom(mem3, cp);
            } else {
                wal3.recover(mem3);
            }
        }
        assertThat(mem3.get(bytes("rk-0"))).isEqualTo(bytes("v3-0"));
        assertThat(mem3.get(bytes("rk-39"))).isEqualTo(bytes("v2-39"));
        assertThat(mem3.get(bytes("rk-100"))).isEqualTo(bytes("v1-100"));
        assertThat(mem3.get(bytes("rk-10"))).isNull();
    }

    @Test
    void preallocatedSegmentsHandleCleanZeroTailRestartAndContinuation() throws Exception {
        Path walDir = tempDir.resolve("prealloc-wal");
        // 小段尺寸 128 KB + preallocate=true + ioMode=AUTO（Windows 自动回退 NIO，Linux 启用 DIRECT）
        WALConfig cfg = new WALConfig(walDir, 128 * 1024, WALConfig.FsyncPolicy.ALWAYS,
                4, WALConfig.IoMode.AUTO, true);
        MemTable mem1 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal = new WALManager(cfg)) {
            assertThat(wal.preallocateEnabled()).isTrue();
            WALStorageEngine engine = new WALStorageEngine(wal, mem1);
            for (int i = 0; i < 60; i++) {
                engine.put(bytes("pk-" + i), bytes("pv-" + i));
            }
        }

        // 重启恢复并继续往同一预分配段续写
        MemTable mem2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal2.recover(mem2);
            assertThat(stats.recordsApplied()).isEqualTo(60);
            assertThat(stats.corruptedRecordsDiscarded()).isZero();
            WALStorageEngine engine2 = new WALStorageEngine(wal2, mem2);
            for (int i = 60; i < 120; i++) {
                engine2.put(bytes("pk-" + i), bytes("pv-" + i));
            }
        }

        // 第三次重启验证前 60 条 + 续写 60 条全部完整
        MemTable mem3 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal3 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal3.recover(mem3);
            assertThat(stats.recordsApplied()).isEqualTo(120);
            assertThat(stats.corruptedRecordsDiscarded()).isZero();
        }
        for (int i = 0; i < 120; i++) {
            assertThat(mem3.get(bytes("pk-" + i))).isEqualTo(bytes("pv-" + i));
        }
    }

    @Test
    void batchWritePreservesStrictWalFirstAtomicityOnFailure() throws Exception {
        Path walDir = tempDir.resolve("wal-first-batch");
        WALConfig cfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO, 4);
        MemTable mem = newMemTable();
        WALManager wal = new WALManager(cfg);
        WALStorageEngine engine = new WALStorageEngine(wal, mem);
        wal.close(); // 强制让 WAL 写入抛 WalWriteException

        List<WriteOp> ops = List.of(
                WriteOp.put(bytes("a"), bytes("1")),
                WriteOp.put(bytes("b"), bytes("2")));
        assertThatThrownBy(() -> engine.applyWriteBatch(0, ops))
                .isInstanceOf(WalWriteException.class);
        assertThat(mem.size()).isZero();
    }

    @Test
    void walShardRouterAlignsKeyStripesDisjointlyAcross16Shards() {
        int[] shardByStripe = new int[256];
        Arrays.fill(shardByStripe, -1);
        for (int i = 0; i < 20_000; i++) {
            byte[] key = bytes("key:" + i);
            int shard = WalShardRouter.route(key, 16);
            int stripe = WalShardRouter.keyStripe(key);
            if (shardByStripe[stripe] == -1) {
                shardByStripe[stripe] = shard;
            } else {
                assertThat(shardByStripe[stripe])
                        .as("每个 keyLock 条带必须唯一归属一个分片")
                        .isEqualTo(shard);
            }
        }
    }
}
