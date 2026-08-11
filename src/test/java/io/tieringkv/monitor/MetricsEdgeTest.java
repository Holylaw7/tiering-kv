package io.tieringkv.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 可观测性边缘（Goal 8）：计数器/仪表快照。 */
class MetricsEdgeTest {

    @Test
    void snapshotIncludesCountersAndGauges() {
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.increment("a");
        metrics.gauge("b", 7);
        assertThat(metrics.snapshot())
                .containsEntry("a", 1L)
                .containsEntry("b", 7L);
    }

    @Test
    void missingCounterZero() {
        Phase28Metrics metrics = new Phase28Metrics();
        assertThat(metrics.counter("missing")).isZero();
        assertThat(metrics.gauge("missing")).isZero();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void gaugeBoundaries(long value) {
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.gauge("g", value);
        assertThat(metrics.gauge("g")).isEqualTo(value);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, 100})
    void incrementAccumulates(int count) {
        Phase28Metrics metrics = new Phase28Metrics();
        for (int i = 0; i < count; i++) {
            metrics.increment("c");
        }
        assertThat(metrics.counter("c")).isEqualTo(count);
    }

    @Test
    void snapshotImmutable() {
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.increment("a");
        java.util.Map<String, Long> snapshot = metrics.snapshot();
        assertThat(snapshot).isNotSameAs(metrics.snapshot());
    }
}
