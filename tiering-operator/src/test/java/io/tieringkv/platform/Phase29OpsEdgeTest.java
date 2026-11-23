package io.tieringkv.platform;

import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.AlertRule;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 运维边缘：全球读、计量、告警参数矩阵。 */
class Phase29OpsEdgeTest {

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 1, 100})
    void strongReadSeqMatrix(long seq) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 100L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", seq)).isEqualTo("a");
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 500})
    void boundedReadSeqMatrix(long seq) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 500L), region -> 100L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", seq)).isEqualTo("a");
    }

    @Test
    void strongReadStaleRejected() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 50L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", 60)).isNull();
    }

    @Test
    void usageMeterTypesAll() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 1);
        meter.record(UsageMeter.MeterType.STORAGE_GB, 2);
        meter.record(UsageMeter.MeterType.EGRESS_GB, 3);
        assertThat(meter.snapshot()).hasSize(3);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.5, 2.0})
    void billingPriceMatrix(double price) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.STORAGE_GB, 4);
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.STORAGE_GB, price));
        assertThat(new MeteredBilling().calculate(meter, plan))
                .isEqualTo(4 * price);
    }

    @Test
    void billingZeroUsageZero() {
        assertThat(new MeteredBilling().calculate(new UsageMeter(),
                new BillingPlan("p", Map.of(
                        UsageMeter.MeterType.REQUESTS, 1.0)))).isZero();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {10, 200})
    void alertGreaterThanMatrix(long value) {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("lag", 100, true,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("lag", value)))
                .hasSize(value > 100 ? 1 : 0);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {5, 50})
    void alertLessThanMatrix(long value) {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("quota", 10, false,
                        AlertRule.Level.CRITICAL)));
        assertThat(manager.evaluate(Map.of("quota", value)))
                .hasSize(value < 10 ? 1 : 0);
    }

    @Test
    void alertManagerEmptyRules() {
        assertThat(new AlertManager(List.of()).evaluate(
                Map.of("a", 1L))).isEmpty();
    }
}
