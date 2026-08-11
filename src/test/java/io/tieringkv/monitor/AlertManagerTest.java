package io.tieringkv.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 告警规则（Goal 7）：阈值评估。 */
class AlertManagerTest {

    @Test
    void alertFiresAboveThreshold() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("replication_lag", 100, true,
                        AlertRule.Level.WARN)));
        List<String> alerts = manager.evaluate(Map.of(
                "replication_lag", 200L));
        assertThat(alerts).contains("WARN:replication_lag=200");
    }

    @Test
    void alertNotFiredBelowThreshold() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("replication_lag", 100, true,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("replication_lag", 50L)))
                .isEmpty();
    }

    @Test
    void criticalLevelReported() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("crdt_clock_skew", 1000, true,
                        AlertRule.Level.CRITICAL)));
        assertThat(manager.evaluate(Map.of("crdt_clock_skew", 5000L)))
                .contains("CRITICAL:crdt_clock_skew=5000");
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 50, 99})
    void belowThresholdNotFire(long value) {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("lag", 100, true,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("lag", value))).isEmpty();
    }

    @Test
    void lessThanRuleFires() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("quota_remaining", 10, false,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("quota_remaining", 5L)))
                .isNotEmpty();
    }

    @Test
    void missingMetricNoAlert() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("missing", 10, true,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("other", 1L))).isEmpty();
    }
}
