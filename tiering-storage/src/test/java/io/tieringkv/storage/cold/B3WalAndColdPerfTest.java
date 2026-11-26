package io.tieringkv.storage.cold;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CacheKey;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cold.filter.BloomFilter;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WalWriteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3 专项测试：WAL Leader-Follower Group Commit、16 分片 S3-FIFO 抗扫描 BlockCache、
 * FastRange 零除法 BloomFilter、Block.findEntry 零全量解码查找与冷层高并发读写。
 */
class B3WalAndColdPerfTest {

    @TempDir
    Path tempDir;

    @Test
    void walGroupCommitCoalescesConcurrentAlwaysFsyncAndRecoversCleanly() throws Exception {
        Path walDir = tempDir.resolve("wal-gc");
        WALConfig cfg = new WALConfig(walDir, 4L << 20, WALConfig.FsyncPolicy.ALWAYS);
        int threads = 16;
        int writesPerThread = 20;
        int totalWrites = threads * writesPerThread;

        try (WALManager wal = new WALManager(cfg)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            try {
                for (int t = 0; t < threads; t++) {
                    int tid = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            byte[] k = ("gc:" + tid + ":" + i).getBytes(StandardCharsets.UTF_8);
                            byte[] v = ("val-" + tid + "-" + i).getBytes(StandardCharsets.UTF_8);
                            wal.append(WALEntry.put(System.currentTimeMillis(), k, v, StorageEngine.NO_TTL, 0));
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            long batches = wal.groupCommitBatches();
            long records = wal.groupCommitRecords();
            System.out.printf("B3-BENCH WAL-GROUP-COMMIT threads=%d records=%d batches=%d avgBatch=%.2f elapsedMs=%d%n",
                    threads, records, batches, records / (double) Math.max(1L, batches), elapsedMs);

            assertThat(records).isEqualTo(totalWrites);
            // 并发写入应出现真实批合并（批次数严格小于总写入数）
            assertThat(batches).isGreaterThan(0).isLessThan(totalWrites);
        }

        // 崩溃恢复校验：320 条记录全部精确还原
        MemTable recovered = MemTable.createForTest(new MutableClock(0), new MemoryManager(64L << 20, 0));
        RecoveryManager.RecoveryStats stats = new RecoveryManager(cfg).recover(recovered);
        assertThat(stats.recordsApplied()).isEqualTo(totalWrites);
        assertThat(recovered.size()).isEqualTo(totalWrites);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < writesPerThread; i++) {
                byte[] k = ("gc:" + t + ":" + i).getBytes(StandardCharsets.UTF_8);
                assertThat(recovered.get(k)).isEqualTo(("val-" + t + "-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }

        // 关闭后追加立即失败不挂起
        WALManager closedWal = new WALManager(new WALConfig(tempDir.resolve("wal-closed"), 1 << 20, WALConfig.FsyncPolicy.NO));
        closedWal.close();
        assertThatThrownBy(() -> closedWal.append(
                WALEntry.put(1L, "k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8), -1L, 0)))
                .isInstanceOf(WalWriteException.class);
    }

    @Test
    void s3FifoBlockCacheShards16AndResistsScanPollution() {
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(1024), pool);
        assertThat(cache.shardCount()).isEqualTo(16);

        // 写入 120 个热块并再次访问使其晋升 Main 队列
        byte[] payload = new byte[64];
        for (int i = 0; i < 120; i++) {
            CacheKey key = new CacheKey(1L, i * 4096L);
            cache.put(key, ByteBuffer.wrap(payload));
            assertThat(cache.get(key)).isNotNull();
            assertThat(cache.get(key)).isNotNull();
        }

        // 灌入 2,000 个一次性顺序扫描块（远超缓存总容量 1024）
        for (int i = 0; i < 2_000; i++) {
            cache.put(new CacheKey(2L, i * 4096L), ByteBuffer.wrap(payload));
        }
        assertThat(cache.size()).isLessThanOrEqualTo(1024);

        // S3-FIFO 抗扫描能力验证：绝大部分 Main 热块应免受一次性扫描污染
        int survivingHot = 0;
        for (int i = 0; i < 120; i++) {
            if (cache.get(new CacheKey(1L, i * 4096L)) != null) {
                survivingHot++;
            }
        }
        System.out.printf("B3-BENCH S3FIFO-SCAN-RESIST hotSurviving=%d/120 totalEvictions=%d%n",
                survivingHot, cache.statistics().snapshot().evictions());
        assertThat(survivingHot).isGreaterThanOrEqualTo(100);

        // 按表失效后释放全部该表块
        cache.invalidate(2L);
        assertThat(cache.get(new CacheKey(2L, 1999 * 4096L))).isNull();
        cache.clear();
        assertThat(cache.size()).isZero();
    }

    @Test
    void blockFindEntryMatchesDecodeWithoutFullBlockDeserializationAndRejectsCorruption() {
        List<KeyValueEntry> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 40; i++) {
            byte[] k = String.format("entry:%03d", i).getBytes(StandardCharsets.UTF_8);
            if (i == 15) {
                entries.add(KeyValueEntry.tombstone(k, now, i + 1L));
            } else if (i == 25) {
                entries.add(KeyValueEntry.live(k, new byte[0], now, -1L, i + 1L));
            } else {
                entries.add(KeyValueEntry.live(k, ("payload-" + i).getBytes(StandardCharsets.UTF_8), now, -1L, i + 1L));
            }
        }
        // 包含高位字节的二进制键（>= 0x80）
        byte[] binaryKey = new byte[]{(byte) 0xFE, (byte) 0x80, 0x01};
        entries.add(KeyValueEntry.live(binaryKey, "bin-val".getBytes(StandardCharsets.UTF_8), now, -1L, 99L));

        byte[] raw = Block.encode(entries);
        ByteBuffer buf = ByteBuffer.wrap(raw);

        for (KeyValueEntry expected : entries) {
            KeyValueEntry foundVerified = Block.findEntry(buf, expected.key(), true);
            KeyValueEntry foundFast = Block.findEntry(buf, expected.key(), false);
            if (expected.value() != null && expected.value().length == 0) {
                // 与 Block.decode 一致：valueLength==0 解码为 null
                assertThat(foundVerified.key()).isEqualTo(expected.key());
                assertThat(foundVerified.value()).isNull();
            } else {
                assertThat(foundVerified).isEqualTo(expected);
                assertThat(foundFast).isEqualTo(expected);
            }
        }
        assertThat(Block.findEntry(buf, "missing-key".getBytes(StandardCharsets.UTF_8), true)).isNull();

        // 篡改块内 1 字节应触发 CRC32C 校验失败
        byte[] corrupted = raw.clone();
        corrupted[corrupted.length - 1] ^= 0x55;
        assertThatThrownBy(() -> Block.findEntry(ByteBuffer.wrap(corrupted),
                "entry:001".getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(ColdCorruptionException.class);
    }

    @Test
    void fastRangeBloomFilterZeroFalseNegativesAndLowFpr() {
        int n = 20_000;
        BloomFilter bloom = new BloomFilter(n, 10.0);
        for (int i = 0; i < n; i++) {
            bloom.put(("bloom-k-" + i).getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < n; i++) {
            assertThat(bloom.mightContain(("bloom-k-" + i).getBytes(StandardCharsets.UTF_8))).isTrue();
        }
        int fp = 0;
        int absentProbes = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < absentProbes; i++) {
            if (bloom.mightContain(("bloom-absent-" + i).getBytes(StandardCharsets.UTF_8))) {
                fp++;
            }
        }
        long elapsedNanos = Math.max(1L, System.nanoTime() - t0);
        double fpr = fp / (double) absentProbes;
        System.out.printf("B3-BENCH FASTRANGE-BLOOM probes=%d fpr=%.4f%% nsPerProbe=%.1f%n",
                absentProbes, fpr * 100.0, elapsedNanos / (double) absentProbes);
        assertThat(fpr).isLessThan(0.01);

        BloomFilter restored = BloomFilter.deserialize(bloom.serialize());
        assertThat(restored).isEqualTo(bloom);
        assertThat(restored.mightContain("bloom-k-12345".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void coldStorageConcurrentPointReadsRunSafelyAlongsideLiveCountAndCompaction() throws Exception {
        Path coldDir = tempDir.resolve("cold-concurrent");
        BlockCache cache = new BlockCache(new CachePolicy(512), new MemoryPool());
        IOStatistics stats = new IOStatistics();
        ColdStorageEngine.Config cfg = new ColdStorageEngine.Config(coldDir, 4096, 10.0, 1L << 20, 16);

        try (ColdStorageEngine cold = new ColdStorageEngine(cfg, cache, stats, true)) {
            int tables = 4;
            int keysPerTable = 400;
            long ver = 1;
            for (int t = 0; t < tables; t++) {
                List<KeyValueEntry> batch = new ArrayList<>(keysPerTable);
                for (int i = 0; i < keysPerTable; i++) {
                    String k = String.format("t%d:k%04d", t, i);
                    batch.add(KeyValueEntry.live(
                            k.getBytes(StandardCharsets.UTF_8),
                            ("val-" + k).getBytes(StandardCharsets.UTF_8),
                            System.currentTimeMillis(), -1L, ver++));
                }
                cold.writeTable(batch);
            }

            int readerThreads = 8;
            int readsPerThread = 4_000;
            ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 1);
            AtomicBoolean stopBg = new AtomicBoolean();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> readers = new ArrayList<>();
            long t0 = System.nanoTime();
            try {
                for (int r = 0; r < readerThreads; r++) {
                    int rid = r;
                    readers.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < readsPerThread; i++) {
                            int t = (rid + i) % tables;
                            int k = (rid * 37 + i) % keysPerTable;
                            String keyStr = String.format("t%d:k%04d", t, k);
                            byte[] got = cold.get(keyStr.getBytes(StandardCharsets.UTF_8));
                            assertThat(got).isEqualTo(("val-" + keyStr).getBytes(StandardCharsets.UTF_8));
                        }
                        return null;
                    }));
                }
                Future<?> bg = pool.submit(() -> {
                    start.await();
                    cold.refreshLiveCount();
                    cold.compactAll();
                    cold.refreshLiveCount();
                    stopBg.set(true);
                    return null;
                });

                start.countDown();
                for (Future<?> f : readers) {
                    f.get(30, TimeUnit.SECONDS);
                }
                bg.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            long totalReads = (long) readerThreads * readsPerThread;
            double opsPerSec = totalReads / ((System.nanoTime() - t0) / 1e9);
            System.out.printf("B3-BENCH COLD-CONCURRENT-READ threads=%d reads=%d ops/s=%.0f hitRate=%.2f%%%n",
                    readerThreads, totalReads, opsPerSec, cache.statistics().snapshot().hitRate() * 100.0);

            assertThat(stopBg.get()).isTrue();
            assertThat(cold.liveCount()).isEqualTo((long) tables * keysPerTable);
            assertThat(cold.tablesSnapshot()).hasSize(1);
        }
    }
}
