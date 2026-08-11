package io.tieringkv.dr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 全球读水位联动（ADR-0129）：真实水位源 + 陈旧度分位。 */
class GlobalReadWatermarkTest {

    @Test
    void providerDrivenBoundedRead() {
        AtomicLong replicated = new AtomicLong(100);
        GlobalReadRouter router = new GlobalReadRouter(
                replicated::get, region -> 50L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 90)).isEqualTo("a");
    }

    @Test
    void providerLagRejectsRead() {
        AtomicLong replicated = new AtomicLong(100);
        GlobalReadRouter router = new GlobalReadRouter(
                replicated::get, region -> 50L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 101)).isNull();
    }

    @Test
    void stalenessPercentiles() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> 0L, ConsistencyMode.BOUNDED);
        router.recordStaleness(10);
        router.recordStaleness(50);
        router.recordStaleness(100);
        long[] percentiles = router.stalenessPercentiles();
        assertThat(percentiles).hasSize(3);
        assertThat(percentiles[0]).isEqualTo(50);
        assertThat(percentiles[2]).isEqualTo(100);
    }

    @Test
    void stalenessPercentilesEmpty() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> 0L, ConsistencyMode.BOUNDED);
        assertThat(router.stalenessPercentiles())
                .containsExactly(0L, 0L, 0L);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 5, 50, 500})
    void stalenessSampleMatrix(long value) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> 0L, ConsistencyMode.BOUNDED);
        router.recordStaleness(value);
        assertThat(router.stalenessMillis()).isEqualTo(value);
    }

    @Test
    void strongReadIgnoresReplicatedWatermark() {
        AtomicLong replicated = new AtomicLong(1000);
        GlobalReadRouter router = new GlobalReadRouter(
                replicated::get, region -> 50L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", 100)).isNull();
    }

    @ParameterizedTest(name = "local {0}")
    @ValueSource(longs = {100, 1000})
    void strongReadLocalWatermark(long local) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> local, ConsistencyMode.STRONG);
        assertThat(router.route("a", local)).isEqualTo("a");
    }
}
