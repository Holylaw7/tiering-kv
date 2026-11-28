package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 3C (K1) 真实磁盘专项测试：
 * O(1) CLEAR_ALL 写放大验证、全策略 fdatasync、旧 Checkpoint 作废与多段恢复、
 * 4 类 Torn Write 断电半写容错、部分分片崩溃隔离与 epoch 严禁复用、
 * ParallelRecoveryManager 与动态重分片（16→8→16）及旧版 WAL 升级兼容。
 */
class WalClearAllBarrierTest {

    @TempDir
    Path tempDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @EnumSource(WALConfig.FsyncPolicy.class)
    void clearAllIsO1InWalBytesAndAlwaysFsyncsAcrossAllPolicies(WALConfig.FsyncPolicy policy) throws Exception {
        Path walDir = tempDir.resolve("o1-" + policy.name());
        int shards = 16;
        int keyCount = policy == WALConfig.FsyncPolicy.ALWAYS ? 2_000 : 50_000;
        WALConfig cfg = new WALConfig(walDir, 4L << 20, policy, shards, WALConfig.IoMode.NIO, false);
        MemTable mem = MemTable.create(new MemoryManager(256L << 20));

        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, mem);
            for (int i = 0; i < keyCount; i++) {
                engine.put(bytes("k-" + i), bytes("val-" + i));
            }
            assertThat(mem.size()).isEqualTo(keyCount);

            // 先刷干净暂存缓冲以便精确测量 clear() 自身写入字节与 fsync 增量
            wal.flushAndForce();
            long bytesBefore = wal.totalBytesWritten();
            long fsyncsBefore = wal.totalFsyncCount();

            engine.clear();

            long bytesDelta = wal.totalBytesWritten() - bytesBefore;
            long fsyncsDelta = wal.totalFsyncCount() - fsyncsBefore;
            assertThat(bytesDelta)
                    .as("CLEAR_ALL 必须为 O(1) 恒定字节数：46B × %d 分片", shards)
                    .isEqualTo((long) WALRecord.CLEAR_ALL_RECORD_SIZE * shards);
            assertThat(fsyncsDelta)
                    .as("所有策略（含 NO）写入 CLEAR_ALL 屏障均必须执行 fdatasync")
                    .isGreaterThanOrEqualTo(shards);
            assertThat(mem.size()).isZero();
            assertThat(mem.memoryManager().usedBytes()).isZero();
            assertThat(wal.committedClearEpoch()).isEqualTo(1L);
        }

        // 重启验证全库为空且 committedClearEpoch 持久收敛
        MemTable restored = MemTable.create(new MemoryManager(256L << 20));
        try (WALManager wal2 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal2.recover(restored);
            assertThat(stats.clearedByBarrier()).isTrue();
            assertThat(stats.committedClearEpoch()).isEqualTo(1L);
            assertThat(stats.corruptedRecordsDiscarded()).isZero();
            assertThat(restored.size()).isZero();
            assertThat(wal2.committedClearEpoch()).isEqualTo(1L);
        }
    }

    @Test
    void multiSegmentOldCheckpointClearAndPostClearReplayBothSingleAndSharded() throws Exception {
        verifyMultiSegmentCheckpointFlow(1);
        verifyMultiSegmentCheckpointFlow(8);
    }

    private void verifyMultiSegmentCheckpointFlow(int shards) throws Exception {
        Path walDir = tempDir.resolve("multi-seg-ckpt-" + shards);
        // 8 KB 小段：确保跨多段轮转
        WALConfig cfg = new WALConfig(walDir, 8 * 1024, WALConfig.FsyncPolicy.EVERY_SEC,
                shards, WALConfig.IoMode.NIO, false);
        MemTable mem = MemTable.create(new MemoryManager(64L << 20));
        byte[] staleCheckpointSnapshot;
        byte[] bigVal = new byte[256];
        Arrays.fill(bigVal, (byte) 'x');

        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, mem);
            // 阶段 1：写入跨多段并生成旧 Checkpoint
            for (int i = 0; i < 300; i++) {
                engine.put(bytes("pre-cp-" + i), bigVal);
            }
            wal.checkpoint(mem);
            staleCheckpointSnapshot = Files.readAllBytes(walDir.resolve("checkpoint.bin"));

            // 阶段 2：旧 Checkpoint 后继续写入跨段
            for (int i = 0; i < 200; i++) {
                engine.put(bytes("mid-" + i), bigVal);
            }

            // 阶段 3：执行 CLEAR_ALL 屏障
            engine.clear();
            assertThat(mem.size()).isZero();
            assertThat(wal.committedClearEpoch()).isEqualTo(1L);

            // 阶段 4：清空后写入新键、删除与带 TTL 键
            for (int i = 0; i < 60; i++) {
                engine.put(bytes("post-" + i), bytes("after-" + i));
            }
            engine.delete(bytes("post-5"));
            engine.put(bytes("post-ttl"), bytes("alive"), 60_000L);
            wal.flushAndForce();
        }

        // 校验 A：正常重启（新空 Checkpoint + 增量回放）
        assertRecoveredMatchesPostClearOnly(cfg, true);

        // 校验 B：模拟宕机发生在「WAL CLEAR_ALL 落盘后、checkpoint.bin 尚未更新前」（恢复旧 Checkpoint）
        Files.write(walDir.resolve("checkpoint.bin"), staleCheckpointSnapshot);
        assertRecoveredMatchesPostClearOnly(cfg, true);

        // 校验 C：删除 checkpoint.bin（验证旧段裁剪未误删含 CLEAR_ALL 的段，全量回放仍精确一致）
        Files.deleteIfExists(walDir.resolve("checkpoint.bin"));
        assertRecoveredMatchesPostClearOnly(cfg, false);
    }

    private void assertRecoveredMatchesPostClearOnly(WALConfig cfg, boolean expectCheckpoint) throws Exception {
        MemTable restored = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal = new WALManager(cfg)) {
            CheckpointManager.Checkpoint cp = wal.loadCheckpoint();
            if (expectCheckpoint) {
                assertThat(cp).isNotNull();
                CheckpointManager.restore(restored, cp);
                wal.recoverFrom(restored, cp);
            } else {
                assertThat(cp).isNull();
                RecoveryManager.RecoveryStats stats = wal.recover(restored);
                assertThat(stats.clearedByBarrier()).isTrue();
                assertThat(stats.committedClearEpoch()).isEqualTo(1L);
            }
        }
        assertThat(restored.get(bytes("pre-cp-0"))).isNull();
        assertThat(restored.get(bytes("mid-0"))).isNull();
        assertThat(restored.get(bytes("post-5"))).isNull();
        assertThat(restored.get(bytes("post-0"))).isEqualTo(bytes("after-0"));
        assertThat(restored.get(bytes("post-59"))).isEqualTo(bytes("after-59"));
        assertThat(restored.get(bytes("post-ttl"))).isEqualTo(bytes("alive"));
        assertThat(restored.size()).isEqualTo(60); // 60 - 1 deleted + 1 ttl
    }

    @Test
    void tornWriteOnClearAllHeaderCrcPreallocZeroPadAndCorruptPayloadDoesNotClearData() throws Exception {
        byte[] validClear = WALRecord.encode(WALEntry.clearAll(System.currentTimeMillis(), 1L));
        assertThat(validClear).hasSize(WALRecord.CLEAR_ALL_RECORD_SIZE);

        // 1) 头部截断（20B）
        verifyTornClearTail("torn-header", Arrays.copyOf(validClear, 20), false);
        // 2) CRC 截断（42B）
        verifyTornClearTail("torn-crc", Arrays.copyOf(validClear, 42), false);
        // 3) 预分配/O_DIRECT 零尾区半写（前 20B 头部 + 后 26B 全 0）
        byte[] zeroPaddedPartial = new byte[WALRecord.CLEAR_ALL_RECORD_SIZE];
        System.arraycopy(validClear, 0, zeroPaddedPartial, 0, 20);
        verifyTornClearTail("torn-prealloc-zero-pad", zeroPaddedPartial, true);
        // 4) 尾部 CRC 单比特翻转
        byte[] flippedCrc = validClear.clone();
        flippedCrc[flippedCrc.length - 1] ^= 0x01;
        verifyTornClearTail("torn-crc-bitflip", flippedCrc, true);
        // 5) 构造带有效 CRC 但违反结构约束（recordVersion=0）的恶意 CLEAR_ALL
        ByteBuffer badBuf = ByteBuffer.allocate(WALRecord.CLEAR_ALL_RECORD_SIZE).order(ByteOrder.BIG_ENDIAN);
        WALRecord.encodeInto(WALEntry.Operation.CLEAR_ALL, System.currentTimeMillis(), null, null, -1L, 0L, badBuf);
        verifyTornClearTail("torn-invalid-zero-epoch", badBuf.array(), true);
    }

    private void verifyTornClearTail(String name, byte[] tornBytes, boolean expectCorruptCounter) throws Exception {
        Path walDir = tempDir.resolve(name);
        WALConfig cfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.ALWAYS, 1, WALConfig.IoMode.NIO, false);
        MemTable mem = MemTable.create(new MemoryManager(32L << 20));
        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, mem);
            for (int i = 0; i < 20; i++) {
                engine.put(bytes("keep-" + i), bytes("v-" + i));
            }
        }

        // 手工向段尾追加半写/损坏 CLEAR_ALL 字节
        Path seg = walDir.resolve("000001.log");
        long validLen = Files.size(seg);
        try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(tornBytes));
            ch.force(true);
        }

        // 重启恢复：必须安全丢弃残尾，绝不误清数据、绝不崩溃
        MemTable restored = MemTable.create(new MemoryManager(32L << 20));
        try (WALManager wal2 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal2.recover(restored);
            assertThat(stats.clearedByBarrier()).isFalse();
            assertThat(stats.committedClearEpoch()).isZero();
            if (expectCorruptCounter) {
                assertThat(stats.corruptedRecordsDiscarded()).isEqualTo(1L);
            }
            assertThat(restored.size()).isEqualTo(20);
            for (int i = 0; i < 20; i++) {
                assertThat(restored.get(bytes("keep-" + i))).isEqualTo(bytes("v-" + i));
            }
        }
        assertThat(Files.size(seg)).isEqualTo(validLen);
    }

    @Test
    void partialShardCrashIgnoresUncommittedClearAndJumpsNextEpochPastTornValue() throws Exception {
        Path walDir = tempDir.resolve("partial-shard-crash");
        int shards = 8;
        WALConfig cfg = new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.ALWAYS,
                shards, WALConfig.IoMode.NIO, false);
        MemTable mem1 = MemTable.create(new MemoryManager(64L << 20));

        try (WALManager wal = new WALManager(cfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, mem1);
            for (int i = 0; i < 40; i++) {
                engine.put(bytes("gen0-" + i), bytes("v0-" + i));
            }
            engine.clear(); // epoch=1，全 8 分片提交
            assertThat(wal.committedClearEpoch()).isEqualTo(1L);

            for (int i = 0; i < 40; i++) {
                engine.put(bytes("gen1-" + i), bytes("v1-" + i));
            }
        }

        // 手工仅向 shard-00..shard-03 追加 CLEAR_ALL(epoch=2)，模拟第 2 次清空写到一半断电
        byte[] tornEpoch2 = WALRecord.encode(WALEntry.clearAll(System.currentTimeMillis(), 2L));
        for (int s = 0; s < 4; s++) {
            Path shardDir = walDir.resolve(String.format("shard-%02d", s));
            List<Long> segs = RecoveryManager.listSegments(shardDir);
            Path lastSeg = shardDir.resolve(String.format("%06d.log", segs.get(segs.size() - 1)));
            try (FileChannel ch = FileChannel.open(lastSeg, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(ByteBuffer.wrap(tornEpoch2));
                ch.force(true);
            }
        }

        // 重启恢复：必须算出 committedClearEpoch=1（忽略未提交的 epoch=2），但 nextClearEpochFloor 必须推进到 2
        MemTable mem2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal2.recover(mem2);
            assertThat(stats.committedClearEpoch()).isEqualTo(1L);
            assertThat(stats.maxObservedClearEpoch()).isEqualTo(2L);
            assertThat(wal2.committedClearEpoch()).isEqualTo(1L);
            assertThat(wal2.nextClearEpochFloor()).isEqualTo(2L);

            // gen0 全部被 epoch=1 清空；gen1 全部 40 键（含 shard-00..03 上早于撕裂 epoch=2 的键）必须 100% 保留！
            assertThat(mem2.size()).isEqualTo(40);
            for (int i = 0; i < 40; i++) {
                assertThat(mem2.get(bytes("gen0-" + i))).isNull();
                assertThat(mem2.get(bytes("gen1-" + i))).isEqualTo(bytes("v1-" + i));
            }

            // 继续写入并再次 clear()：新纪元必须严禁复用 2，直接跳号到 3！
            WALStorageEngine engine2 = new WALStorageEngine(wal2, mem2);
            for (int i = 0; i < 20; i++) {
                engine2.put(bytes("gen1-extra-" + i), bytes("ve-" + i));
            }
            engine2.clear();
            assertThat(wal2.committedClearEpoch())
                    .as("严禁复用撕裂未提交纪元 2，必须跳号至 3")
                    .isEqualTo(3L);
            for (int i = 0; i < 15; i++) {
                engine2.put(bytes("gen3-" + i), bytes("v3-" + i));
            }
        }

        // 第三次重启验证：仅 gen3 存活，gen1/gen1-extra 全部被 epoch=3 彻底清除
        MemTable mem3 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal3 = new WALManager(cfg)) {
            RecoveryManager.RecoveryStats stats = wal3.recover(mem3);
            assertThat(stats.committedClearEpoch()).isEqualTo(3L);
            assertThat(mem3.size()).isEqualTo(15);
            for (int i = 0; i < 15; i++) {
                assertThat(mem3.get(bytes("gen3-" + i))).isEqualTo(bytes("v3-" + i));
            }
            assertThat(mem3.get(bytes("gen1-0"))).isNull();
            assertThat(mem3.get(bytes("gen1-extra-0"))).isNull();
        }
    }

    @Test
    void parallelRecoveryAndResharding16To8To16HonorClearAllBarrier() throws Exception {
        // 1) ParallelRecoveryManager 多段 CLEAR_ALL
        Path parallelDir = tempDir.resolve("parallel-recovery");
        WALConfig singleCfg = new WALConfig(parallelDir, 1 << 20, WALConfig.FsyncPolicy.ALWAYS, 1);
        MemTable memP = MemTable.create(new MemoryManager(32L << 20));
        try (WALManager wal = new WALManager(singleCfg)) {
            WALStorageEngine engine = new WALStorageEngine(wal, memP);
            for (int i = 0; i < 20; i++) {
                engine.put(bytes("p-old-" + i), bytes("old"));
            }
            wal.rotate();
            engine.put(bytes("p-mid"), bytes("mid"));
            wal.rotate();
            // 直接写 CLEAR_ALL 不删旧段以构造多段并行解析输入
            wal.append(WALEntry.clearAll(System.currentTimeMillis(), 1L));
            wal.rotate();
            for (int i = 0; i < 10; i++) {
                engine.put(bytes("p-new-" + i), bytes("new-" + i));
            }
        }
        MemTable restoredP = MemTable.create(new MemoryManager(32L << 20));
        RecoveryManager.RecoveryStats pStats = new ParallelRecoveryManager(singleCfg, 4).recover(restoredP);
        assertThat(pStats.clearedByBarrier()).isTrue();
        assertThat(pStats.committedClearEpoch()).isEqualTo(1L);
        assertThat(restoredP.size()).isEqualTo(10);
        assertThat(restoredP.get(bytes("p-old-0"))).isNull();
        assertThat(restoredP.get(bytes("p-mid"))).isNull();
        assertThat(restoredP.get(bytes("p-new-9"))).isEqualTo(bytes("new-9"));

        // 2) 动态重分片 16 -> 8 -> 16 与 CLEAR_ALL 屏障协同
        Path reshardDir = tempDir.resolve("reshard-clear");
        WALConfig cfg16 = new WALConfig(reshardDir, 1 << 20, WALConfig.FsyncPolicy.EVERY_SEC, 16);
        WALConfig cfg8 = new WALConfig(reshardDir, 1 << 20, WALConfig.FsyncPolicy.EVERY_SEC, 8);
        MemTable m1 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal = new WALManager(cfg16)) {
            WALStorageEngine engine = new WALStorageEngine(wal, m1);
            for (int i = 0; i < 80; i++) {
                engine.put(bytes("r-pre-" + i), bytes("v1"));
            }
            engine.clear();
            for (int i = 0; i < 30; i++) {
                engine.put(bytes("r-post-" + i), bytes("v2-" + i));
            }
        }

        // 16 -> 8
        MemTable m2 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal2 = new WALManager(cfg8)) {
            RecoveryManager.RecoveryStats st = wal2.recover(m2);
            assertThat(st.committedClearEpoch()).isEqualTo(1L);
            assertThat(m2.size()).isEqualTo(30);
            assertThat(m2.get(bytes("r-pre-0"))).isNull();
            assertThat(m2.get(bytes("r-post-15"))).isEqualTo(bytes("v2-15"));

            WALStorageEngine engine2 = new WALStorageEngine(wal2, m2);
            engine2.clear();
            assertThat(wal2.committedClearEpoch()).isEqualTo(2L);
            engine2.put(bytes("r-final"), bytes("ok"));
        }

        // 8 -> 16
        MemTable m3 = MemTable.create(new MemoryManager(64L << 20));
        try (WALManager wal3 = new WALManager(cfg16)) {
            CheckpointManager.Checkpoint cp = wal3.loadCheckpoint();
            if (cp != null) {
                CheckpointManager.restore(m3, cp);
                wal3.recoverFrom(m3, cp);
            } else {
                wal3.recover(m3);
            }
            assertThat(wal3.committedClearEpoch()).isEqualTo(2L);
        }
        assertThat(m3.size()).isEqualTo(1);
        assertThat(m3.get(bytes("r-final"))).isEqualTo(bytes("ok"));
        assertThat(m3.get(bytes("r-post-15"))).isNull();
    }

    @Test
    void legacyWalWithoutClearAllAndRecordValidationWorkCleanly() throws Exception {
        // 旧版无 CLEAR_ALL WAL 无损升级
        Path legacyDir = tempDir.resolve("legacy-wal-compat");
        WALConfig legacyCfg = new WALConfig(legacyDir, 1 << 20, WALConfig.FsyncPolicy.ALWAYS, 1);
        try (SegmentManager segs = new SegmentManager(legacyDir);
             WALWriter writer = new WALWriter(segs, WALConfig.FsyncPolicy.ALWAYS)) {
            long now = System.currentTimeMillis();
            writer.append(WALEntry.put(now, bytes("legacy-1"), bytes("v1"), -1, 1));
            writer.append(WALEntry.put(now, bytes("legacy-2"), bytes("v2"), -1, 2));
            writer.append(WALEntry.delete(now, bytes("legacy-1"), 3));
        }
        assertThat(Files.exists(legacyDir.resolve(WALManager.CLEAR_EPOCH_FILE))).isFalse();

        MemTable mem = MemTable.create(new MemoryManager(32L << 20));
        try (WALManager wal = new WALManager(legacyCfg)) {
            RecoveryManager.RecoveryStats stats = wal.recover(mem);
            assertThat(stats.clearedByBarrier()).isFalse();
            assertThat(stats.committedClearEpoch()).isZero();
            assertThat(mem.get(bytes("legacy-1"))).isNull();
            assertThat(mem.get(bytes("legacy-2"))).isEqualTo(bytes("v2"));

            WALStorageEngine engine = new WALStorageEngine(wal, mem);
            engine.clear();
            assertThat(wal.committedClearEpoch()).isEqualTo(1L);
            assertThat(mem.size()).isZero();
        }

        // 参数与编解码边界校验
        assertThatThrownBy(() -> WALEntry.clearAll(System.currentTimeMillis(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WALEntry.clearAll(System.currentTimeMillis(), -5L))
                .isInstanceOf(IllegalArgumentException.class);

        // 非法 TYPE_CLEAR_ALL（keyLen != 0）抛出 WalCorruptionException
        byte[] badKeyClear = buildRawRecord(WALRecord.TYPE_CLEAR_ALL, bytes("k"), new byte[0], 1L);
        assertThatThrownBy(() -> WALRecord.decode(badKeyClear))
                .isInstanceOf(WalCorruptionException.class);

        // 非法 TYPE_DELETE（valueLen != 0）抛出 WalCorruptionException
        byte[] badDelete = buildRawRecord(WALRecord.TYPE_DELETE, bytes("k"), bytes("v"), 1L);
        assertThatThrownBy(() -> WALRecord.decode(badDelete))
                .isInstanceOf(WalCorruptionException.class);

        // 未知类型抛出 WalCorruptionException
        byte[] badType = buildRawRecord((byte) 99, bytes("k"), new byte[0], 1L);
        assertThatThrownBy(() -> WALRecord.decode(badType))
                .isInstanceOf(WalCorruptionException.class);

        // WAL 已关闭时 clear() 必须抛出 WalWriteException 且绝不误清 MemTable
        Path closedDir = tempDir.resolve("closed-wal-clear");
        WALConfig closedCfg = new WALConfig(closedDir, 1 << 20, WALConfig.FsyncPolicy.NO, 4);
        MemTable keepMem = MemTable.create(new MemoryManager(16L << 20));
        WALManager closedWal = new WALManager(closedCfg);
        WALStorageEngine closedEngine = new WALStorageEngine(closedWal, keepMem);
        closedEngine.put(bytes("survive"), bytes("yes"));
        closedWal.close();
        assertThatThrownBy(closedEngine::clear).isInstanceOf(WalWriteException.class);
        assertThat(keepMem.get(bytes("survive"))).isEqualTo(bytes("yes"));

        // 空批次 / 单条批次 / 跨分片批次与 clear() 组合边界
        Path batchDir = tempDir.resolve("batch-edge-clear");
        WALConfig batchCfg = new WALConfig(batchDir, 1 << 20, WALConfig.FsyncPolicy.NO, 4);
        MemTable batchMem = MemTable.create(new MemoryManager(16L << 20));
        try (WALManager bw = new WALManager(batchCfg)) {
            WALStorageEngine be = new WALStorageEngine(bw, batchMem);
            assertThat(be.applyWriteBatch(0, List.of())).isZero();
            assertThat(be.applyWriteBatch(0, List.of(io.tieringkv.storage.WriteOp.put(bytes("s1"), bytes("v1"))))).isZero();
            assertThat(be.applyWriteBatch(0, List.of(io.tieringkv.storage.WriteOp.delete(bytes("s1"))))).isEqualTo(1L);
            // 跨分片多条写+删
            List<io.tieringkv.storage.WriteOp> cross = List.of(
                    io.tieringkv.storage.WriteOp.put(bytes("cx-a"), bytes("1")),
                    io.tieringkv.storage.WriteOp.put(bytes("cx-b"), bytes("2")),
                    io.tieringkv.storage.WriteOp.put(bytes("cx-c"), bytes("3")),
                    io.tieringkv.storage.WriteOp.put(bytes("cx-d"), bytes("4")),
                    io.tieringkv.storage.WriteOp.delete(bytes("cx-b")));
            be.applyWriteBatch(-1, cross);
            assertThat(be.exists(bytes("cx-b"))).isFalse();
            assertThat(be.get(bytes("cx-a"))).isEqualTo(bytes("1"));
            be.clear();
            assertThat(be.size()).isZero();
            // 损坏 clear.epoch 文件时容错归零，不阻塞启动
            Files.writeString(batchDir.resolve(WALManager.CLEAR_EPOCH_FILE), "clearEpoch=not-a-number\n");
            assertThat(WALManager.readClearEpochFile(batchDir)).isZero();
        }
    }

    private static byte[] buildRawRecord(byte type, byte[] key, byte[] value, long version) {
        int payloadLen = WALRecord.HEADER_SIZE + key.length + value.length;
        byte[] out = new byte[payloadLen + WALRecord.CHECKSUM_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(WALRecord.MAGIC);
        buf.put(WALRecord.VERSION);
        buf.put(type);
        buf.putLong(System.currentTimeMillis());
        buf.putInt(key.length);
        buf.putInt(value.length);
        buf.putLong(version);
        buf.putLong(-1L);
        buf.put(key);
        buf.put(value);
        long crc = ChecksumValidator.crc32c(out, payloadLen);
        buf.putLong(crc);
        return out;
    }
}
