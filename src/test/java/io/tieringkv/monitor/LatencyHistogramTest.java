package io.tieringkv.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-096：延迟直方图与滚动窗口。 */
class LatencyHistogramTest {

    @Test
    void percentilesUseBucketUpperBounds() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < 95; i++) {
            histogram.record(400_000L); // 0.4ms → 500µs 桶
        }
        for (int i = 0; i < 5; i++) {
            histogram.record(8_000_000L); // 8ms → 10ms 桶
        }

        assertThat(histogram.count()).isEqualTo(100);
        assertThat(histogram.percentileMillis(0.50)).isEqualTo(0.5);
        assertThat(histogram.percentileMillis(0.95)).isEqualTo(0.5);
        assertThat(histogram.percentileMillis(0.99)).isEqualTo(10.0);
        assertThat(histogram.maxMillis()).isEqualTo(8);
    }

    @Test
    void emptyHistogramReportsZero() {
        LatencyHistogram histogram = new LatencyHistogram();

        assertThat(histogram.count()).isZero();
        assertThat(histogram.percentileMillis(0.95)).isZero();
    }

    @Test
    void mergeAddsCounts() {
        LatencyHistogram first = new LatencyHistogram();
        LatencyHistogram second = new LatencyHistogram();
        first.record(200_000L);
        second.record(200_000L);
        second.record(6_000_000L);

        first.mergeFrom(second);

        assertThat(first.count()).isEqualTo(3);
        assertThat(first.percentileMillis(0.99)).isEqualTo(10.0);
    }

    @Test
    void windowedStatsFallBackToCumulativeWhenIdle() {
        LatencyStats stats = new LatencyStats();
        stats.record(1_000_000L);

        LatencyStats.Snapshot snapshot = stats.snapshot();

        assertThat(snapshot.samples()).isEqualTo(1);
        assertThat(snapshot.p95Millis()).isGreaterThan(0);
    }
}
