package io.tieringkv.monitor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** G0-5B-4 / ADR-0373：延迟统计去监视器化，语义与旧实现一致。 */
class LatencyStatsConcurrencyTest {

    /** 旧实现的分位数计算（固定桶上界），作为对照基准。 */
    private static LatencyHistogram reference(int[] micros) {
        LatencyHistogram h = new LatencyHistogram();
        for (int micro : micros) {
            h.record(micro * 1_000L);
        }
        return h;
    }

    @Test
    void 桶边界归类与旧实现一致() {
        int[] micros = {1, 50, 51, 100, 101, 250, 251, 500, 501, 1_000, 1_001,
                2_500, 2_501, 5_000, 5_001, 10_000, 10_001, 25_000, 25_001,
                50_000, 50_001, 100_000, 100_001, 250_000, 250_001,
                500_000, 500_001, 1_000_000, 1_000_001, 2_500_001, 5_000_001, 9_000_000};
        LatencyHistogram reference = reference(micros);

        LatencyStats stats = new LatencyStats();
        for (int micro : micros) {
            stats.record(micro * 1_000L);
        }
        LatencyStats.Snapshot snapshot = stats.snapshot();

        assertThat(snapshot.samples()).isEqualTo(micros.length);
        assertThat(snapshot.p50Millis()).isEqualTo(Math.min(reference.percentileMillis(0.50), reference.maxMillis()));
        assertThat(snapshot.p95Millis()).isEqualTo(Math.min(reference.percentileMillis(0.95), reference.maxMillis()));
        assertThat(snapshot.p99Millis()).isEqualTo(Math.min(reference.percentileMillis(0.99), reference.maxMillis()));
        assertThat(snapshot.maxMillis()).isEqualTo(reference.maxMillis());
    }

    @Test
    void 空样本返回全零() {
        LatencyStats.Snapshot snapshot = new LatencyStats().snapshot();
        assertThat(snapshot.samples()).isZero();
        assertThat(snapshot.p50Millis()).isZero();
        assertThat(snapshot.maxMillis()).isZero();
    }

    @Test
    void 单样本的百分位等于该桶上界且不超过最大值() {
        LatencyStats stats = new LatencyStats();
        stats.record(1_200_000L); // 1.2ms → 2.5ms 桶，最大值封顶 1ms
        LatencyStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.samples()).isEqualTo(1);
        assertThat(snapshot.p50Millis()).isEqualTo(1.0);
        assertThat(snapshot.maxMillis()).isEqualTo(1);
    }

    @Test
    void 并发记录样本数准确且不阻塞快照() throws Exception {
        LatencyStats stats = new LatencyStats();
        int threads = 8;
        int perThread = 25_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> snapshotSamples = new ArrayList<>();
        pool.submit(() -> {
            start.await();
            for (int i = 0; i < 200; i++) {
                stats.snapshot();
                snapshotSamples.add(1L);
            }
            return null;
        });
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    stats.record(10_000L);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        LatencyStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.samples()).isEqualTo((long) threads * perThread);
        assertThat(snapshotSamples).hasSize(200);
    }
}