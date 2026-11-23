package io.tieringkv.gateway;

import io.tieringkv.gateway.AutonomousTrafficController.Adjustment;
import io.tieringkv.gateway.AutonomousTrafficController.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流量自治控制器（ADR-0151）：限幅 + 熔断 + 回滚。 */
class AutonomousTrafficControllerTest {

    @Test
    void adjustAppliesWithinStep() {
        Fixture fixture = fixture();
        Adjustment adjustment = fixture.controller().adjust("r1", 70);
        assertThat(adjustment.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(fixture.quota().quota("r1")).isEqualTo(70);
    }

    @Test
    void adjustClampsToMaxQuota() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 150);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 1.0, 10, 200);
        controller.adjust("r1", 10_000);
        assertThat(quota.quota("r1")).isEqualTo(200);
    }

    @Test
    void adjustClampsToMinQuota() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 20);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 1.0, 10, 200);
        controller.adjust("r1", 1);
        assertThat(quota.quota("r1")).isEqualTo(10);
    }

    @Test
    void adjustLimitsStepByFraction() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 100);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 0.5, 10, 500);
        Adjustment adjustment = controller.adjust("r1", 200);
        assertThat(adjustment.target()).isEqualTo(150);
        assertThat(quota.quota("r1")).isEqualTo(150);
    }

    @Test
    void unknownRegionInitializesAtMinQuota() {
        Fixture fixture = fixture();
        fixture.controller().adjust("r9", 50);
        assertThat(fixture.quota().quota("r9")).isEqualTo(15);
    }

    @Test
    void circuitOpenRejects() {
        Fixture fixture = fixture();
        fixture.controller().openCircuit("overload detected");
        Adjustment adjustment = fixture.controller().adjust("r1", 80);
        assertThat(adjustment.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(adjustment.reason()).contains("circuit open");
    }

    @Test
    void resetCircuitRestoresAdjustments() {
        Fixture fixture = fixture();
        fixture.controller().openCircuit("overload");
        assertThat(fixture.controller().adjust("r1", 80).outcome())
                .isEqualTo(Outcome.REJECTED);
        fixture.controller().resetCircuit();
        assertThat(fixture.controller().adjust("r1", 70).outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    @Test
    void rollbackRestoresOriginalQuota() {
        Fixture fixture = fixture();
        fixture.controller().adjust("r1", 100);
        fixture.controller().adjust("r1", 150);
        fixture.controller().rollback();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
    }

    @Test
    void rollbackRestoresMultipleRegions() {
        Fixture fixture = fixture();
        fixture.controller().adjust("r1", 100);
        fixture.controller().adjust("r2", 120);
        fixture.controller().rollback();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
        assertThat(fixture.quota().quota("r2")).isEqualTo(50);
    }

    @Test
    void rollbackClearsHistory() {
        Fixture fixture = fixture();
        fixture.controller().adjust("r1", 100);
        fixture.controller().rollback();
        fixture.controller().adjust("r1", 120);
        fixture.controller().rollback();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
    }

    @Test
    void invalidFractionRejected() {
        assertThatThrownBy(() -> new AutonomousTrafficController(
                new RegionQuota(), 0, 10, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousTrafficController(
                new RegionQuota(), 1.5, 10, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidBoundsRejected() {
        assertThatThrownBy(() -> new AutonomousTrafficController(
                new RegionQuota(), 0.5, -1, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousTrafficController(
                new RegionQuota(), 0.5, 200, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjustmentCarriesPreviousAndTarget() {
        Fixture fixture = fixture();
        Adjustment adjustment = fixture.controller().adjust("r1", 70);
        assertThat(adjustment.region()).isEqualTo("r1");
        assertThat(adjustment.previous()).isEqualTo(50);
        assertThat(adjustment.target()).isEqualTo(70);
    }

    @Test
    void zeroFractionRejected() {
        assertThatThrownBy(() -> new AutonomousTrafficController(
                new RegionQuota(), 0.0, 10, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "fraction {0}")
    @ValueSource(doubles = {0.1, 0.5, 1.0})
    void parameterizedFractions(double fraction) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        Fixture fixture = new Fixture(quota,
                new AutonomousTrafficController(quota,
                        fraction, 10, 200));
        fixture.controller().adjust("r1", 100);
        long maxDelta = Math.max(1, Math.round(50 * fraction));
        assertThat(quota.quota("r1")).isEqualTo(50 + maxDelta);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(longs = {20, 70, 150})
    void parameterizedTargets(long target) {
        Fixture fixture = fixture();
        Adjustment adjustment = fixture.controller().adjust("r1",
                target);
        assertThat(adjustment.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(fixture.quota().quota("r1")).isBetween(10L, 200L);
    }

    @ParameterizedTest(name = "min {0} max {1}")
    @CsvSource({"10,200", "50,100", "1,1000"})
    void parameterizedBounds(long min, long max) {
        AutonomousTrafficController controller =
                new AutonomousTrafficController(new RegionQuota(),
                        1.0, min, max);
        controller.adjust("r1", Long.MAX_VALUE);
        assertThat(controller.adjust("r1", Long.MAX_VALUE)
                .target()).isLessThanOrEqualTo(max);
    }

    @Test
    void concurrentAdjustsStable() throws Exception {
        Fixture fixture = fixture();
        Thread[] threads = new Thread[6];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    fixture.controller().adjust("r1", 100);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(fixture.quota().quota("r1")).isBetween(10L, 200L);
    }

    @Test
    void circuitPersistsAcrossAdjustments() {
        Fixture fixture = fixture();
        fixture.controller().openCircuit("overload");
        fixture.controller().adjust("r1", 80);
        fixture.controller().adjust("r1", 120);
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
    }

    @Test
    void rollbackAfterUnknownRegionInitialization() {
        Fixture fixture = fixture();
        fixture.controller().adjust("r9", 50);
        fixture.controller().rollback();
        assertThat(fixture.quota().quota("r9")).isEqualTo(10);
    }

    private static Fixture fixture() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        quota.setQuota("r2", 50);
        return new Fixture(quota, new AutonomousTrafficController(
                quota, 0.5, 10, 200));
    }

    private record Fixture(RegionQuota quota,
                           AutonomousTrafficController controller) {
    }
}
