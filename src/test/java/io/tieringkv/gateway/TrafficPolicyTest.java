package io.tieringkv.gateway;

import io.tieringkv.gateway.PriorityRouter.Priority;
import io.tieringkv.gateway.TrafficPolicy.PolicyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全球流量策略（ADR-0149）：QPS/配额映射 + 优先级准入。 */
class TrafficPolicyTest {

    @Test
    void qpsMapping() {
        TrafficPolicy policy = policy();
        assertThat(policy.qps(Priority.LOW)).isEqualTo(1000);
        assertThat(policy.qps(Priority.NORMAL)).isEqualTo(5000);
        assertThat(policy.qps(Priority.HIGH)).isEqualTo(2000);
    }

    @Test
    void quotaFractionMapping() {
        TrafficPolicy policy = policy();
        assertThat(policy.quotaFor(1000, Priority.LOW))
                .isEqualTo(100);
        assertThat(policy.quotaFor(1000, Priority.NORMAL))
                .isEqualTo(500);
        assertThat(policy.quotaFor(1000, Priority.HIGH))
                .isEqualTo(400);
    }

    @Test
    void allowsWithinQps() {
        TrafficPolicy policy = policy();
        assertThat(policy.allows(Priority.LOW)).isTrue();
        assertThat(policy.allows(Priority.LOW)).isTrue();
    }

    @Test
    void rejectsWhenQpsExhausted() {
        TrafficPolicy policy = policy();
        for (int i = 0; i < 1000; i++) {
            assertThat(policy.allows(Priority.LOW)).isTrue();
        }
        assertThat(policy.allows(Priority.LOW)).isFalse();
    }

    @Test
    void resetRestoresCapacity() {
        TrafficPolicy policy = policy();
        for (int i = 0; i < 1000; i++) {
            policy.allows(Priority.LOW);
        }
        policy.reset();
        assertThat(policy.allows(Priority.LOW)).isTrue();
    }

    @Test
    void unknownPriorityNotConfigured() {
        TrafficPolicy policy = new TrafficPolicy(Map.of(
                Priority.HIGH, new PolicyEntry(10, 1.0)));
        assertThat(policy.qps(Priority.LOW)).isZero();
        assertThat(policy.allows(Priority.LOW)).isFalse();
        assertThat(policy.quotaFor(100, Priority.LOW)).isZero();
    }

    @Test
    void currentTracksAllows() {
        TrafficPolicy policy = policy();
        policy.allows(Priority.NORMAL);
        policy.allows(Priority.NORMAL);
        assertThat(policy.current(Priority.NORMAL)).isEqualTo(2);
    }

    @Test
    void negativeQpsRejected() {
        assertThatThrownBy(() -> new PolicyEntry(-1, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidFractionRejected() {
        assertThatThrownBy(() -> new PolicyEntry(10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyEntry(10, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAllowsExactLimit() throws Exception {
        TrafficPolicy policy = new TrafficPolicy(Map.of(
                Priority.NORMAL, new PolicyEntry(1000, 1.0)));
        java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    if (policy.allows(Priority.NORMAL)) {
                        accepted.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(accepted.get()).isEqualTo(1000);
    }

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(ints = {1, 50, 500})
    void parameterizedQpsLimits(int qps) {
        TrafficPolicy policy = new TrafficPolicy(Map.of(
                Priority.NORMAL, new PolicyEntry(qps, 1.0)));
        for (int i = 0; i < qps; i++) {
            assertThat(policy.allows(Priority.NORMAL)).isTrue();
        }
        assertThat(policy.allows(Priority.NORMAL)).isFalse();
    }

    @ParameterizedTest(name = "fraction {0}")
    @CsvSource({"0.0,0", "0.25,25", "0.5,50", "1.0,100"})
    void parameterizedQuotaFractions(double fraction, long expected) {
        TrafficPolicy policy = new TrafficPolicy(Map.of(
                Priority.NORMAL, new PolicyEntry(100, fraction)));
        assertThat(policy.quotaFor(100, Priority.NORMAL))
                .isEqualTo(expected);
    }

    private static TrafficPolicy policy() {
        Map<Priority, PolicyEntry> entries = new EnumMap<>(
                Priority.class);
        entries.put(Priority.LOW, new PolicyEntry(1000, 0.1));
        entries.put(Priority.NORMAL, new PolicyEntry(5000, 0.5));
        entries.put(Priority.HIGH, new PolicyEntry(2000, 0.4));
        return new TrafficPolicy(entries);
    }
}
