package io.tieringkv.storage.tiering;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.VectorIndexSyncStorageEngine;
import io.tieringkv.storage.WriteOp;
import io.tieringkv.storage.cold.ColdCorruptionException;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.FlushManager;
import io.tieringkv.storage.cold.Manifest;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.CheckpointManager;
import io.tieringkv.storage.wal.ChecksumValidator;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 3C (K1) 冷热分层一致性与高并发专项测试：
 * Manifest v1 升级兼容与 v2 clearEpoch CRC 校验、WAL 先提交 / Manifest 滞后自动收敛防僵尸键复活、
 * 清空后新落冷 SSTable 保留、copyUp/Flush/Migration/Checkpoint 与 clear() 高并发压测及崩溃恢复字节级一致验证。
 */
class TieringClearConsistencyTest {

    @TempDir
    Path tempDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void manifestV1UpgradesLosslesslyToV2AndDetectsClearEpochTamper() throws Exception {
        Path coldDir = tempDir.resolve("manifest-v1-compat");
        Files.createDirectories(coldDir);

        // 先用 SSTableWriter 写入一张真实的冷表，并手工落盘老版 Manifest v1（17B 头，无 clearEpoch）
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(coldDir, 1L, 10, 10.0, 4096)) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 10; i++) {
                String k = String.format("legacy-%02d", i);
                writer.writeEntry(KeyValueEntry.live(bytes(k), bytes("val-" + i), now, -1L, i + 1));
            }
            meta = writer.finish();
        }
        writeLegacyManifestV1(coldDir, List.of(meta));

        // 1) 无损读取 v1：clearEpoch 默认 0，冷表数据可读
        Manifest.ManifestState v1State = Manifest.readState(coldDir);
        assertThat(v1State.clearEpoch()).isZero();
        assertThat(v1State.tables()).hasSize(1);

        try (ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(coldDir))) {
            assertThat(cold.clearEpoch()).isZero();
            assertThat(cold.get(bytes("legacy-05"))).isEqualTo(bytes("val-5"));

            // 2) 清空推进到 Manifest v2 (clearEpoch=4)
            cold.clearToEpoch(4L);
            assertThat(cold.clearEpoch()).isEqualTo(4L);
            assertThat(cold.tablesSnapshot()).isEmpty();
            assertThat(cold.get(bytes("legacy-05"))).isNull();
        }

        // 3) 重开验证 v2 持久收敛
        try (ColdStorageEngine cold2 = new ColdStorageEngine(ColdStorageEngine.Config.defaults(coldDir))) {
            assertThat(cold2.clearEpoch()).isEqualTo(4L);
            assertThat(cold2.tablesSnapshot()).isEmpty();
        }

        // 4) 篡改 Manifest v2 的 clearEpoch 字节 -> CRC32C 必须报错
        Path manifestFile = coldDir.resolve("manifest.bin");
        byte[] raw = Files.readAllBytes(manifestFile);
        assertThat(raw[4]).isEqualTo(Manifest.VERSION_V2);
        raw[13] ^= 0x01; // 破坏 clearEpoch 字节
        Files.write(manifestFile, raw);
        assertThatThrownBy(() -> Manifest.readState(coldDir))
                .isInstanceOf(ColdCorruptionException.class);

        // 5) Manifest 异常格式边界：负 clearEpoch 写拒绝、截断 v2、未知版本、负 count
        assertThatThrownBy(() -> Manifest.write(coldDir, -1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Path badDir = tempDir.resolve("manifest-bad-variants");
        Files.createDirectories(badDir);
        // 截断 v2（仅 20 字节 < HEADER_SIZE_V2=25）
        byte[] shortV2 = new byte[20];
        ByteBuffer.wrap(shortV2).order(ByteOrder.BIG_ENDIAN).putInt(Manifest.MAGIC).put(Manifest.VERSION_V2);
        Files.write(badDir.resolve("manifest.bin"), shortV2);
        assertThatThrownBy(() -> Manifest.readState(badDir)).isInstanceOf(ColdCorruptionException.class);

        // 未知版本 99
        byte[] badVer = new byte[Manifest.HEADER_SIZE_V2];
        ByteBuffer.wrap(badVer).order(ByteOrder.BIG_ENDIAN).putInt(Manifest.MAGIC).put((byte) 99);
        Files.write(badDir.resolve("manifest.bin"), badVer);
        assertThatThrownBy(() -> Manifest.readState(badDir)).isInstanceOf(ColdCorruptionException.class);

        // 合法 CRC 但 clearEpoch < 0
        byte[] negEpoch = new byte[Manifest.HEADER_SIZE_V2];
        ByteBuffer negBuf = ByteBuffer.wrap(negEpoch).order(ByteOrder.BIG_ENDIAN);
        negBuf.putInt(Manifest.MAGIC).put(Manifest.VERSION_V2).putLong(0L).putLong(-2L).putInt(0);
        long negCrc = ChecksumValidator.crc32c(negEpoch, 13, negEpoch.length - 13);
        negBuf.putLong(5, negCrc);
        Files.write(badDir.resolve("manifest.bin"), negEpoch);
        assertThatThrownBy(() -> Manifest.readState(badDir)).isInstanceOf(ColdCorruptionException.class);
    }

    @Test
    void nonWalTieringClearMigrationLogClearAndColdBatchOpsBehaveCorrectly() throws Exception {
        // 无 WAL 场景下的 TieringReadStorageEngine.clear()、copyUp、applyWriteBatch(TTL + delete)
        Path coldDir = tempDir.resolve("non-wal-cold");
        Path migDir = tempDir.resolve("non-wal-mig");
        MemTable mem = MemTable.create(new MemoryManager(32L << 20));
        try (ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(coldDir));
             TierWorkerPool pool = new TierWorkerPool(1, "test-mig");
             MigrationScheduler scheduler = new MigrationScheduler(
                     pool, new MigrationLog(migDir), cold, null, mem, new StorageMetrics(), 1, 0)) {
            TieringReadStorageEngine tiered = new TieringReadStorageEngine(mem, cold);
            tiered.put(bytes("c1"), bytes("10"));
            tiered.put(bytes("c2"), bytes("hello"));
            FlushManager.flush(mem, null, cold);
            assertThat(mem.size()).isZero();
            assertThat(tiered.get(bytes("c1"))).isEqualTo(bytes("10"));

            // 冷层键 copyUp (increment) + applyWriteBatch（带 TTL 覆写冷键 + 删除冷键）
            assertThat(tiered.increment(bytes("c1"), 5L)).isEqualTo(15L);
            long delMask = tiered.applyWriteBatch(0, List.of(
                    WriteOp.put(bytes("c2"), bytes("ttl-override"), 30_000L),
                    WriteOp.delete(bytes("c2"))));
            assertThat(delMask).isNotZero();
            assertThat(tiered.get(bytes("c2"))).isNull();

            // 写入迁移日志后测试 clearLog()
            mem.put(bytes("m1"), bytes("mv1"));
            KeyValueEntry mEntry = mem.getEntry(bytes("m1"));
            scheduler.submitBatch(List.of(MigrationTask.pending(mEntry, "memory", "cold")));
            scheduler.clearLog();
            assertThat(scheduler.recoverAndResume()).isEmpty();

            // 无 WAL 路径 clear() 清空热层与冷层
            tiered.clear();
            assertThat(tiered.size()).isZero();
            assertThat(cold.tablesSnapshot()).isEmpty();
        }
    }

    @Test
    void crashAfterWalClearBeforeColdClearReconcilesZeroZombieKeys() throws Exception {
        Path walDir = tempDir.resolve("zombie-wal");
        Path coldDir = tempDir.resolve("zombie-cold");
        WALConfig walCfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.ALWAYS, 8);
        ColdStorageEngine.Config coldCfg = ColdStorageEngine.Config.defaults(coldDir);

        MemTable mem1 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal1 = new WALManager(walCfg);
             ColdStorageEngine cold1 = new ColdStorageEngine(coldCfg)) {
            WALStorageEngine walStorage = new WALStorageEngine(wal1, mem1);
            TieringReadStorageEngine tiered = new TieringReadStorageEngine(walStorage, cold1);

            for (int i = 0; i < 80; i++) {
                tiered.put(bytes("cold-" + i), bytes("cold-val-" + i));
            }
            FlushManager.flush(mem1, wal1, cold1);
            assertThat(cold1.tablesSnapshot()).isNotEmpty();
            assertThat(cold1.clearEpoch()).isZero();

            for (int i = 0; i < 40; i++) {
                tiered.put(bytes("hot-" + i), bytes("hot-val-" + i));
            }

            // 模拟宕机窗口：WAL 已全分片提交 CLEAR_ALL(1)，但尚未执行 cold.clearToEpoch(1) 即断电！
            walStorage.clear();
            assertThat(wal1.committedClearEpoch()).isEqualTo(1L);
            assertThat(cold1.clearEpoch()).isZero();
            assertThat(cold1.tablesSnapshot()).isNotEmpty(); // 冷表仍残留在 Manifest v2(epoch=0) 中
        }

        // 模拟服务端重启对账（复现 Main.java 完整启动顺序）
        MemTable mem2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(walCfg);
             ColdStorageEngine cold2 = new ColdStorageEngine(coldCfg)) {
            CheckpointManager.Checkpoint cp = wal2.loadCheckpoint();
            if (cp != null) {
                CheckpointManager.restore(mem2, cp);
                wal2.recoverFrom(mem2, cp);
            } else {
                wal2.recover(mem2);
            }
            boolean reconciled = cold2.reconcileClearEpoch(wal2.committedClearEpoch());
            wal2.advanceEpochFloor(cold2.clearEpoch());

            assertThat(reconciled).isTrue();
            assertThat(cold2.clearEpoch()).isEqualTo(1L);
            assertThat(cold2.tablesSnapshot()).isEmpty();
            assertThat(cold2.liveCount()).isZero();

            TieringReadStorageEngine tiered2 = new TieringReadStorageEngine(new WALStorageEngine(wal2, mem2), cold2);
            assertThat(tiered2.size()).isZero();
            for (int i = 0; i < 80; i++) {
                assertThat(tiered2.get(bytes("cold-" + i))).isNull();
            }
            for (int i = 0; i < 40; i++) {
                assertThat(tiered2.get(bytes("hot-" + i))).isNull();
            }
        }
    }

    @Test
    void postClearColdSstableSurvivesCrashRestartEvenWithoutNewWalCheckpoint() throws Exception {
        Path walDir = tempDir.resolve("preserve-wal");
        Path coldDir = tempDir.resolve("preserve-cold");
        WALConfig walCfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.EVERY_SEC, 8);
        ColdStorageEngine.Config coldCfg = ColdStorageEngine.Config.defaults(coldDir);

        MemTable mem1 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal1 = new WALManager(walCfg);
             ColdStorageEngine cold1 = new ColdStorageEngine(coldCfg)) {
            WALStorageEngine walStorage = new WALStorageEngine(wal1, mem1);
            TieringReadStorageEngine tiered = new TieringReadStorageEngine(walStorage, cold1);

            for (int i = 0; i < 50; i++) {
                tiered.put(bytes("old-" + i), bytes("old-val-" + i));
            }
            FlushManager.flush(mem1, wal1, cold1);
            assertThat(cold1.tablesSnapshot()).hasSize(1);

            // 完整冷热清空（epoch=1）
            tiered.clear();
            assertThat(wal1.committedClearEpoch()).isEqualTo(1L);
            assertThat(cold1.clearEpoch()).isEqualTo(1L);
            assertThat(cold1.tablesSnapshot()).isEmpty();

            // 清空后写入新键并 Flush 到冷表 T2（Manifest 携带 clearEpoch=1）
            for (int i = 0; i < 50; i++) {
                tiered.put(bytes("new-cold-" + i), bytes("nc-" + i));
            }
            FlushManager.flush(mem1, wal1, cold1);
            assertThat(cold1.tablesSnapshot()).hasSize(1);
            assertThat(cold1.clearEpoch()).isEqualTo(1L);

            for (int i = 0; i < 20; i++) {
                tiered.put(bytes("new-hot-" + i), bytes("nh-" + i));
            }
            wal1.flushAndForce();
        }

        // 删除 checkpoint.bin 强制从头重放 WAL（WAL 仍包含 CLEAR_ALL(1)），验证冷层 T2 绝不被误删
        Files.deleteIfExists(walDir.resolve("checkpoint.bin"));

        MemTable mem2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(walCfg);
             ColdStorageEngine cold2 = new ColdStorageEngine(coldCfg)) {
            RecoveryManager.RecoveryStats stats = wal2.recover(mem2);
            assertThat(stats.clearedByBarrier()).isTrue();
            assertThat(stats.committedClearEpoch()).isEqualTo(1L);

            boolean reconciled = cold2.reconcileClearEpoch(wal2.committedClearEpoch());
            wal2.advanceEpochFloor(cold2.clearEpoch());
            assertThat(reconciled)
                    .as("冷层已处于 clearEpoch=1，不应再次误清清空后落盘的新 SSTable")
                    .isFalse();
            assertThat(cold2.tablesSnapshot()).hasSize(1);

            TieringReadStorageEngine tiered2 = new TieringReadStorageEngine(new WALStorageEngine(wal2, mem2), cold2);
            for (int i = 0; i < 50; i++) {
                assertThat(tiered2.get(bytes("old-" + i))).isNull();
                assertThat(tiered2.get(bytes("new-cold-" + i))).isEqualTo(bytes("nc-" + i));
            }
            for (int i = 0; i < 20; i++) {
                assertThat(tiered2.get(bytes("new-hot-" + i))).isEqualTo(bytes("nh-" + i));
            }
        }
    }

    @Test
    void concurrentPutDeleteBatchCopyUpFlushCheckpointAndClearMatchesRecoveryByteForByte() throws Exception {
        Path walDir = tempDir.resolve("stress-wal");
        Path coldDir = tempDir.resolve("stress-cold");
        WALConfig walCfg = new WALConfig(walDir, 256 * 1024, WALConfig.FsyncPolicy.EVERY_SEC, 16);
        ColdStorageEngine.Config coldCfg = ColdStorageEngine.Config.defaults(coldDir);

        MemoryManager memoryManager = new MemoryManager(128L << 20);
        MemTable mem1 = MemTable.create(memoryManager);
        BlockCache blockCache = new BlockCache(new CachePolicy(4 << 20), new MemoryPool());
        IOStatistics ioStats = new IOStatistics();

        Map<String, String> snapshotBeforeShutdown = new TreeMap<>();

        try (WALManager wal1 = new WALManager(walCfg);
             ColdStorageEngine cold1 = new ColdStorageEngine(coldCfg, blockCache, ioStats, false)) {
            WALStorageEngine walStorage = new WALStorageEngine(wal1, mem1);
            HotKeyPolicy hotPolicy = new HotKeyPolicy(10_000L, 2, 1024, 5_000L);
            HotKeyReadCache hotCache = new HotKeyReadCache(new HotKeyDetector(hotPolicy), hotPolicy, walStorage);
            TieringReadStorageEngine tiered = new TieringReadStorageEngine(
                    new HotKeyStorageEngine(walStorage, hotCache), cold1);
            VectorIndexSyncStorageEngine top = new VectorIndexSyncStorageEngine(tiered, new VectorIndexStore(4));

            // 预置一批冷键用于压测 copyUp (increment / append / persist / expireAt) 与 clear() 并发竞态
            for (int i = 0; i < 40; i++) {
                top.put(bytes("cnt-" + i), bytes("10"));
            }
            FlushManager.flush(mem1, wal1, cold1);

            int writerThreads = 6;
            AtomicBoolean stop = new AtomicBoolean(false);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 3);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < writerThreads; t++) {
                    final int tid = t;
                    futures.add(pool.submit(() -> {
                        await(start);
                        for (int i = 0; i < 200; i++) {
                            String k = "w" + tid + "-k" + (i % 30);
                            if (i % 5 == 0) {
                                top.applyWriteBatch(-1, List.of(
                                        WriteOp.put(bytes(k), bytes("b-" + i)),
                                        WriteOp.delete(bytes("w" + tid + "-del-" + (i % 10)))));
                            } else if (i % 7 == 0) {
                                tiered.increment(bytes("cnt-" + (i % 40)), 1L);
                            } else if (i % 9 == 0) {
                                top.delete(bytes(k));
                            } else {
                                top.put(bytes(k), bytes("v-" + tid + "-" + i));
                            }
                        }
                    }));
                }
                // 并发 Flush 线程
                Future<?> flushFuture = pool.submit(() -> {
                    await(start);
                    while (!stop.get()) {
                        try {
                            FlushManager.flush(mem1, wal1, cold1);
                            Thread.sleep(5);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                // 并发 Checkpoint 线程
                Future<?> cpFuture = pool.submit(() -> {
                    await(start);
                    while (!stop.get()) {
                        try {
                            wal1.checkpoint(mem1);
                            Thread.sleep(5);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                // 并发周期 clear() 线程
                Future<?> clearFuture = pool.submit(() -> {
                    await(start);
                    for (int c = 0; c < 4; c++) {
                        try {
                            Thread.sleep(8);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        top.clear();
                    }
                });

                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                clearFuture.get(30, TimeUnit.SECONDS);
                stop.set(true);
                flushFuture.get(15, TimeUnit.SECONDS);
                cpFuture.get(15, TimeUnit.SECONDS);

                // 确保 WAL 全部缓冲落盘（不额外做 checkpoint，模拟直接断电）
                wal1.flushAndForce();
                cold1.flushPending();

                // 采集停机前权威合并视图（热层 + 冷层）
                try (StorageIterator it = top.iterator()) {
                    while (it.hasNext()) {
                        KeyValueEntry e = it.next();
                        snapshotBeforeShutdown.put(
                                new String(e.key(), StandardCharsets.UTF_8),
                                new String(e.value(), StandardCharsets.UTF_8));
                    }
                }
                assertThat(wal1.committedClearEpoch()).isGreaterThanOrEqualTo(4L);
                assertThat(cold1.clearEpoch()).isEqualTo(wal1.committedClearEpoch());
            } finally {
                pool.shutdownNow();
            }
        }

        // 重启恢复并比对每一个键的字节级一致性（热层 + 冷层）
        MemTable mem2 = MemTable.create(new MemoryManager(128L << 20));
        try (WALManager wal2 = new WALManager(walCfg);
             ColdStorageEngine cold2 = new ColdStorageEngine(coldCfg)) {
            CheckpointManager.Checkpoint cp = wal2.loadCheckpoint();
            if (cp != null) {
                CheckpointManager.restore(mem2, cp);
                wal2.recoverFrom(mem2, cp);
            } else {
                wal2.recover(mem2);
            }
            cold2.reconcileClearEpoch(wal2.committedClearEpoch());
            wal2.advanceEpochFloor(cold2.clearEpoch());

            StorageEngine tiered2 = new TieringReadStorageEngine(new WALStorageEngine(wal2, mem2), cold2);
            Map<String, String> recovered = new TreeMap<>();
            try (StorageIterator it = tiered2.iterator()) {
                while (it.hasNext()) {
                    KeyValueEntry e = it.next();
                    recovered.put(
                            new String(e.key(), StandardCharsets.UTF_8),
                            new String(e.value(), StandardCharsets.UTF_8));
                }
            }
            assertThat(recovered)
                    .as("高并发 put/delete/batch/copyUp/flush/checkpoint/clear 压测后，重启恢复状态必须与停机前严格一致")
                    .isEqualTo(snapshotBeforeShutdown);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeLegacyManifestV1(Path directory, List<SSTableMeta> tables) throws Exception {
        int size = Manifest.HEADER_SIZE;
        for (SSTableMeta table : tables) {
            size += 8 + 8 + 8 + 4 + table.firstKey().length + 4 + table.lastKey().length
                    + 4 + table.fileName().length();
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(Manifest.MAGIC);
        buf.put(Manifest.VERSION);
        buf.putInt(tables.size());
        buf.putLong(0L);
        for (SSTableMeta table : tables) {
            buf.putLong(table.id());
            buf.putLong(table.entryCount());
            buf.putLong(table.fileSize());
            buf.putInt(table.firstKey().length);
            buf.put(table.firstKey());
            buf.putInt(table.lastKey().length);
            buf.put(table.lastKey());
            buf.putInt(table.fileName().length());
            buf.put(table.fileName().getBytes(StandardCharsets.US_ASCII));
        }
        long crc = ChecksumValidator.crc32c(buf.array(), Manifest.HEADER_SIZE, size - Manifest.HEADER_SIZE);
        buf.putLong(9, crc);
        Files.write(directory.resolve("manifest.bin"), buf.array());
    }
}
