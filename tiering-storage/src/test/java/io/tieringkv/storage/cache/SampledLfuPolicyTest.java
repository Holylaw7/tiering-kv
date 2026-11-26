package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 内联 Morris-LFU 采样淘汰、SkipList 动态层级抽样、putOwned 与 SegmentLFU 有界队列测试。
 */
class SampledLfuPolicyTest {

    @Test
    void inlineMorrisCounterGrowsLogarithmicallyAndDecaysOverTime() {
        MutableClock clock = new MutableClock(60_000L);
        MemTable table = MemTable.createForTest(clock, new MemoryManager(1L << 30, 0));
        table.setLfuDecayIntervalMillis(60_000L);

        byte[] hot = "hot:key".getBytes(StandardCharsets.UTF_8);
        byte[] cold = "cold:key".getBytes(StandardCharsets.UTF_8);
        table.put(hot, "v".getBytes(StandardCharsets.UTF_8));
        table.put(cold, "v".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 2_000; i++) {
            table.get(hot);
        }
        KeyValueEntry hotEntry = table.getEntry(hot);
        KeyValueEntry coldEntry = table.getEntry(cold);
        int hotFreq = hotEntry.effectiveLfu(clock.nowMillis(), 60_000L);
        int coldFreq = coldEntry.effectiveLfu(clock.nowMillis(), 60_000L);

        assertThat(coldFreq).isEqualTo(KeyValueEntry.LFU_INIT_VAL);
        assertThat(hotFreq).isGreaterThan(coldFreq + 5).isLessThanOrEqualTo(KeyValueEntry.LFU_MAX_VAL);

        // 推进 10 个衰减周期：冷键归零，热键相应衰减但仍显著更高
        clock.advance(10 * 60_000L);
        assertThat(coldEntry.effectiveLfu(clock.nowMillis(), 60_000L)).isZero();
        assertThat(hotEntry.effectiveLfu(clock.nowMillis(), 60_000L)).isEqualTo(Math.max(0, hotFreq - 10));
    }

    @Test
    void sampledLfuRetainsHotKeysAndEvictsColdKeysWithExclusionFilter() {
        MutableClock clock = new MutableClock(120_000L);
        MemoryManager memory = new MemoryManager(1L << 30, 0);
        MemTable table = MemTable.createForTest(clock, memory);

        Set<String> hotSet = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String k = "hot:" + i;
            hotSet.add(k);
            byte[] kb = k.getBytes(StandardCharsets.UTF_8);
            table.putOwned(kb, "payload-hot".getBytes(StandardCharsets.UTF_8), MemTable.NO_TTL);
            for (int r = 0; r < 800; r++) {
                table.get(kb);
            }
        }
        for (int i = 0; i < 200; i++) {
            byte[] kb = ("cold:" + i).getBytes(StandardCharsets.UTF_8);
            table.putOwned(kb, "payload-cold".getBytes(StandardCharsets.UTF_8), MemTable.NO_TTL);
        }

        // 排除 cold:0 模拟迁移队列已在途
        byte[] excludedKey = "cold:0".getBytes(StandardCharsets.UTF_8);
        SampledLfuPolicy policy = new SampledLfuPolicy(
                table, 60_000L, 16, clock, key -> !Arrays.equals(key, excludedKey));
        assertThat(policy.name()).isEqualTo("sampled-lfu");
        assertThat(policy.tracksViaAccessEvents()).isFalse();

        int hotEvicted = 0;
        for (int i = 0; i < 40; i++) {
            EvictionCandidate candidate = policy.selectCandidate();
            assertThat(candidate).isNotNull();
            assertThat(candidate.key()).isNotEqualTo(excludedKey);
            String keyStr = new String(candidate.key(), StandardCharsets.UTF_8);
            if (hotSet.contains(keyStr)) {
                hotEvicted++;
            }
            table.removePhysical(candidate.key());
        }
        // 热键绝大多数应被完整保留
        assertThat(hotEvicted).isZero();
    }

    @Test
    void trackingStorageEngineEvictsViaSampledLfuUnderMemoryPressure() {
        MutableClock clock = new MutableClock(60_000L);
        // 仅允许约 10 条记录驻留内存，关闭堆阈值避免环境波动
        MemoryManager memory = new MemoryManager(1_200L, 0);
        MemTable table = MemTable.createForTest(clock, memory);
        SampledLfuPolicy policy = new SampledLfuPolicy(table, 60_000L, 16, clock, null);
        AtomicInteger migrated = new AtomicInteger();
        EvictionManager eviction = new EvictionManager(
                table, memory, policy, entry -> {
                    migrated.incrementAndGet();
                    return MigrationResult.SUCCESS;
                }, clock, 64);
        TrackingStorageEngine tracked = new TrackingStorageEngine(table, eviction, clock);

        byte[] hot = "vip".getBytes(StandardCharsets.UTF_8);
        tracked.put(hot, "v".repeat(32).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 1_000; i++) {
            assertThat(tracked.get(hot)).isNotNull();
        }
        for (int i = 0; i < 60; i++) {
            tracked.put(("cold:" + i).getBytes(StandardCharsets.UTF_8),
                    "x".repeat(32).getBytes(StandardCharsets.UTF_8));
        }

        assertThat(migrated.get()).isGreaterThan(30);
        assertThat(memory.usedBytes()).isLessThanOrEqualTo(memory.maxBytes());
        assertThat(tracked.get(hot)).isNotNull();
    }

    @Test
    void sparseMemTableSamplingAlwaysFindsLiveKeys() {
        MutableClock clock = new MutableClock(1000L);
        MemTable table = MemTable.createForTest(clock, new MemoryManager(1L << 20, 0));
        table.put("only-1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
        table.put("only-2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));

        for (int round = 0; round < 20; round++) {
            List<KeyValueEntry> samples = table.sampleLiveEntries(8, clock.nowMillis());
            assertThat(samples).isNotEmpty();
            assertThat(samples).allMatch(e -> e.isLive(clock.nowMillis()));
        }
    }

    @Test
    void segmentLfuPolicyBoundsBufferUnderIdleHighTraffic() {
        SegmentLFUPolicy policy = new SegmentLFUPolicy(4);
        int total = SegmentLFUPolicy.MAX_BUFFERED_EVENTS + 5_000;
        byte[] key = "hot".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < total; i++) {
            policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.GET, i, 16));
        }
        assertThat(policy.buffered()).isLessThanOrEqualTo(SegmentLFUPolicy.MAX_BUFFERED_EVENTS);
        assertThat(policy.selectCandidate()).isNotNull();
        assertThat(policy.buffered()).isZero();
    }

    @Test
    void concurrentReadsWithSampledLfuOutperformGlobalLockLfu() throws Exception {
        int keyCount = 4_000;
        int threads = 8;
        int opsPerThread = 25_000;
        byte[][] keys = new byte[keyCount][];
        byte[] val = "v".repeat(64).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < keyCount; i++) {
            keys[i] = ("k:" + i).getBytes(StandardCharsets.UTF_8);
        }

        long sampledNanos = measureConcurrentReads(keys, val, threads, opsPerThread, true);
        long legacyNanos = measureConcurrentReads(keys, val, threads, opsPerThread, false);
        double speedup = (double) legacyNanos / Math.max(1L, sampledNanos);
        System.out.printf("B2-BENCH CONCURRENT-READ threads=%d ops=%d sampledMs=%.1f legacyLfuMs=%.1f speedup=%.2fx%n",
                threads, threads * opsPerThread, sampledNanos / 1e6, legacyNanos / 1e6, speedup);
        assertThat(sampledNanos).isLessThan(legacyNanos);
    }

    private static long measureConcurrentReads(byte[][] keys, byte[] val,
                                               int threads, int opsPerThread,
                                               boolean sampled) throws Exception {
        MemoryManager memory = new MemoryManager(1L << 30, 0);
        MemTable table = MemTable.createForTest(System::currentTimeMillis, memory);
        EvictionPolicy policy = sampled
                ? new SampledLfuPolicy(table, 60_000L, 16)
                : new LFUPolicy(60_000L);
        EvictionManager eviction = new EvictionManager(table, memory, policy, e -> MigrationResult.SUCCESS);
        TrackingStorageEngine engine = new TrackingStorageEngine(table, eviction);
        for (byte[] key : keys) {
            engine.put(key, val);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int tid = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        int mask = keys.length;
                        int idx = tid * 131;
                        for (int i = 0; i < opsPerThread; i++) {
                            engine.get(keys[(idx + i) % mask]);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            long begin = System.nanoTime();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            return System.nanoTime() - begin;
        } finally {
            pool.shutdownNow();
            table.close();
        }
    }
}
