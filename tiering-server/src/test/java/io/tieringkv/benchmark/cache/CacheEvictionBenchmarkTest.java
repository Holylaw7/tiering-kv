package io.tieringkv.benchmark.cache;

import io.tieringkv.storage.cache.AccessEvent;
import io.tieringkv.storage.cache.HotnessTracker;
import io.tieringkv.storage.cache.LFUPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存基准（Phase 3）：LFU 1M 次访问（100K 键）的查找/更新延迟与内存估算；
 * 淘汰决策延迟（100K / 1M 条目）。目标：淘汰决策 P99 &lt; 1ms。
 */
@Tag("benchmark")
class CacheEvictionBenchmarkTest {

    private static final int KEYS = 100_000;
    private static final int LOOKUP_SAMPLES = 500_000;
    private static final int UPDATE_SAMPLES = 500_000;
    private static final int DECISION_SAMPLES = 10_000;

    @Test
    void lfuTrackingLatency() {
        LFUPolicy policy = new LFUPolicy(new HotnessTracker(60_000));
        long start = System.nanoTime();
        for (int i = 0; i < KEYS; i++) {
            policy.onAccess(putEvent(i));
        }
        long loadNanos = System.nanoTime() - start;

        for (int i = 0; i < 10_000; i++) {
            policy.onAccess(getEvent(randomKey()));
        }

        long[] lookup = new long[LOOKUP_SAMPLES];
        long[] update = new long[UPDATE_SAMPLES];
        long lookupStart = System.nanoTime();
        for (int i = 0; i < LOOKUP_SAMPLES; i++) {
            long t0 = System.nanoTime();
            policy.onAccess(getEvent(randomKey()));
            lookup[i] = System.nanoTime() - t0;
        }
        long lookupNanos = System.nanoTime() - lookupStart;

        long updateStart = System.nanoTime();
        for (int i = 0; i < UPDATE_SAMPLES; i++) {
            long t0 = System.nanoTime();
            policy.onAccess(putEvent(randomKey()));
            update[i] = System.nanoTime() - t0;
        }
        long updateNanos = System.nanoTime() - updateStart;

        Arrays.sort(lookup);
        Arrays.sort(update);
        double lookupAvgUs = lookupNanos / 1000.0 / LOOKUP_SAMPLES;
        double updateAvgUs = updateNanos / 1000.0 / UPDATE_SAMPLES;
        double lookupP99Us = lookup[(int) (LOOKUP_SAMPLES * 0.99)] / 1000.0;
        double updateP99Us = update[(int) (UPDATE_SAMPLES * 0.99)] / 1000.0;
        long estimatedOverheadBytes = policy.tracker().size() * 96L;

        System.out.printf(Locale.ROOT,
                "CACHE-BENCH LFU load(100K)=%.0fms lookup avg=%.2fus p99=%.2fus update avg=%.2fus p99=%.2fus entries=%d estOverhead=%dMB%n",
                loadNanos / 1_000_000.0, lookupAvgUs, lookupP99Us, updateAvgUs, updateP99Us,
                policy.tracker().size(), estimatedOverheadBytes / 1024 / 1024);

        assertThat(lookupP99Us / 1000.0).isLessThan(0.1); // 100μs 宽松上限
        assertThat(updateP99Us / 1000.0).isLessThan(0.1);
    }

    @Test
    void evictionDecisionLatency() {
        for (int dataset : new int[]{100_000, 1_000_000}) {
            LFUPolicy policy = new LFUPolicy(new HotnessTracker(60_000));
            for (int i = 0; i < dataset; i++) {
                policy.onAccess(putEvent(i));
            }

            long[] latencies = new long[DECISION_SAMPLES];
            for (int i = 0; i < DECISION_SAMPLES; i++) {
                long t0 = System.nanoTime();
                policy.selectCandidate();
                latencies[i] = System.nanoTime() - t0;
            }
            Arrays.sort(latencies);
            double avgUs = Arrays.stream(latencies).average().orElse(0) / 1000.0;
            double p99Us = latencies[(int) (DECISION_SAMPLES * 0.99)] / 1000.0;

            System.out.printf(Locale.ROOT,
                    "CACHE-BENCH EVICT dataset=%d avg=%.2fus p99=%.2fus%n",
                    dataset, avgUs, p99Us);

            assertThat(p99Us / 1000.0)
                    .as("eviction decision P99 dataset=%d", dataset)
                    .isLessThan(1.0); // 目标 < 1ms
        }
    }

    private static AccessEvent getEvent(int key) {
        return new AccessEvent(keyBytes(key), AccessEvent.AccessOperation.GET, 0, 0);
    }

    private static AccessEvent putEvent(int key) {
        return new AccessEvent(keyBytes(key), AccessEvent.AccessOperation.PUT, 0, 96);
    }

    private static int randomKey() {
        return ThreadLocalRandom.current().nextInt(KEYS);
    }

    private static byte[] keyBytes(int key) {
        return String.format(Locale.ROOT, "cache-key:%08d", key).getBytes(StandardCharsets.UTF_8);
    }
}
