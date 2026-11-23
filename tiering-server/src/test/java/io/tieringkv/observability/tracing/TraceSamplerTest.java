package io.tieringkv.observability.tracing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 追踪采样（ADR-0154）：确定性采样 + 边界。 */
class TraceSamplerTest {

    @Test
    void rateOneSamplesAll() {
        TraceSampler sampler = new TraceSampler(1.0);
        for (int i = 0; i < 100; i++) {
            assertThat(sampler.sample("trace-" + i)).isTrue();
        }
    }

    @Test
    void rateZeroSamplesNone() {
        TraceSampler sampler = new TraceSampler(0.0);
        for (int i = 0; i < 100; i++) {
            assertThat(sampler.sample("trace-" + i)).isFalse();
        }
    }

    @Test
    void deterministicForSameTraceId() {
        TraceSampler sampler = new TraceSampler(0.5);
        boolean first = sampler.sample("trace-42");
        for (int i = 0; i < 10; i++) {
            assertThat(sampler.sample("trace-42"))
                    .isEqualTo(first);
        }
    }

    @Test
    void halfRateApproximatesHalf() {
        TraceSampler sampler = new TraceSampler(0.5);
        long sampled = 0;
        for (int i = 0; i < 2000; i++) {
            if (sampler.sample("trace-" + i)) {
                sampled++;
            }
        }
        assertThat(sampled).isBetween(700L, 1300L);
    }

    @Test
    void invalidRatesRejected() {
        assertThatThrownBy(() -> new TraceSampler(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceSampler(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTraceIdNotSampled() {
        assertThat(new TraceSampler(1.0).sample("")).isFalse();
        assertThat(new TraceSampler(1.0).sample(null)).isFalse();
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.9})
    void parameterizedRates(double rate) {
        TraceSampler sampler = new TraceSampler(rate);
        long sampled = 0;
        for (int i = 0; i < 1000; i++) {
            if (sampler.sample("trace-" + i)) {
                sampled++;
            }
        }
        double expected = 1000 * rate;
        assertThat(sampled).isBetween(
                (long) (expected - 200), (long) (expected + 200));
    }

    @ParameterizedTest(name = "trace {0}")
    @ValueSource(strings = {"a", "abc", "trace-123"})
    void parameterizedTraceIds(String traceId) {
        TraceSampler sampler = new TraceSampler(1.0);
        assertThat(sampler.sample(traceId)).isTrue();
    }

    @Test
    void samplingIsStableAcrossInstances() {
        TraceSampler first = new TraceSampler(0.5);
        TraceSampler second = new TraceSampler(0.5);
        for (int i = 0; i < 100; i++) {
            assertThat(first.sample("trace-" + i))
                    .isEqualTo(second.sample("trace-" + i));
        }
    }
}
