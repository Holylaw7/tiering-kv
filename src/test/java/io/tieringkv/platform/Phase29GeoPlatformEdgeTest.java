package io.tieringkv.platform;

import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.AlertRule;
import io.tieringkv.replication.crdt.CrdtScaleSimulator;
import io.tieringkv.replication.crdt.HybridClockCalibrator;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 Geo/平台边缘：CRDT 规模、时钟、全球读、计费、告警。 */
class Phase29GeoPlatformEdgeTest {

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 4, 10})
    void crdtSimulatorNodeCounts(int nodes) {
        CrdtScaleSimulator simulator = new CrdtScaleSimulator(nodes, 20);
        simulator.run(3);
        assertThat(simulator.registerCount()).isEqualTo(nodes * 20);
    }

    @Test
    void clockCalibratorPositiveAndNegative() {
        HybridClockCalibrator calibrator = new HybridClockCalibrator();
        assertThat(calibrator.estimateOffset(List.of(
                new HybridClockCalibrator.Sample(1000, 900))))
                .isEqualTo(-100);
        assertThat(calibrator.estimateOffset(List.of(
                new HybridClockCalibrator.Sample(1000, 1100))))
                .isEqualTo(100);
    }

    @ParameterizedTest(name = "offset {0}")
    @ValueSource(longs = {-500, 0, 500})
    void clockAdjustBoundaries(long offset) {
        HybridClockCalibrator calibrator = new HybridClockCalibrator();
        assertThat(calibrator.adjust(1_000, offset))
                .isEqualTo(1_000 - offset);
    }

    @Test
    void strongReadRequiresExactWatermark() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 100L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", 100)).isEqualTo("a");
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {1, 500, 1000})
    void boundedReadSeqVolume(long seq) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 1_000L), region -> 100L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", seq)).isEqualTo(
                seq <= 1_000 ? "a" : null);
    }

    @Test
    void usageMeterAllTypes() {
        UsageMeter meter = new UsageMeter();
        for (UsageMeter.MeterType type : UsageMeter.MeterType.values()) {
            meter.record(type, 1);
        }
        assertThat(meter.snapshot()).hasSize(
                UsageMeter.MeterType.values().length);
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(longs = {0, 1, 1000})
    void usageMeterAmounts(long amount) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.EGRESS_GB, amount);
        assertThat(meter.get(UsageMeter.MeterType.EGRESS_GB))
                .isEqualTo(amount);
    }

    @Test
    void meteredBillingMultipleDimensions() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 100);
        meter.record(UsageMeter.MeterType.STORAGE_GB, 2);
        meter.record(UsageMeter.MeterType.EGRESS_GB, 3);
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01,
                UsageMeter.MeterType.STORAGE_GB, 2.0,
                UsageMeter.MeterType.EGRESS_GB, 0.5));
        assertThat(new MeteredBilling().calculate(meter, plan))
                .isEqualTo(1.0 + 4.0 + 1.5);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 500})
    void alertThresholdBoundaries(long value) {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("lag", 100, true,
                        AlertRule.Level.WARN)));
        assertThat(manager.evaluate(Map.of("lag", value)))
                .hasSize(value > 100 ? 1 : 0);
    }

    @Test
    void alertMultipleRules() {
        AlertManager manager = new AlertManager(List.of(
                new AlertRule("a", 10, true, AlertRule.Level.WARN),
                new AlertRule("b", 10, true, AlertRule.Level.CRITICAL)));
        assertThat(manager.evaluate(Map.of("a", 20L, "b", 5L)))
                .hasSize(1);
    }
}
