package io.tieringkv.gateway;

import io.tieringkv.gateway.GlobalTrafficAutonomy.RegionAdjustment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全球流量自治（ADR-0157）：多地域联合调整 + 回滚。 */
class GlobalTrafficAutonomyTest {

    @Test
    void adjustAllAppliesConfiguredRegions() {
        Fixture fixture = fixture();
        List<RegionAdjustment> results =
                fixture.autonomy().adjustAll(Map.of(
                        "r1", 80L, "r2", 90L));
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(RegionAdjustment::applied);
    }

    @Test
    void adjustAllSkipsUnconfiguredTargets() {
        Fixture fixture = fixture();
        List<RegionAdjustment> results =
                fixture.autonomy().adjustAll(Map.of(
                        "r1", 80L, "missing", 100L));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).region()).isEqualTo("r1");
    }

    @Test
    void rollbackAllRestoresQuotas() {
        Fixture fixture = fixture();
        fixture.autonomy().adjustAll(Map.of("r1", 80L, "r2", 90L));
        fixture.autonomy().rollbackAll();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
        assertThat(fixture.quota().quota("r2")).isEqualTo(50);
    }

    @Test
    void circuitOpenReflected() {
        Fixture fixture = fixture();
        fixture.controller().openCircuit("failure");
        assertThat(fixture.autonomy().circuitOpen()).isTrue();
        fixture.controller().resetCircuit();
        assertThat(fixture.autonomy().circuitOpen()).isFalse();
    }

    @Test
    void nullTargetsRejected() {
        assertThatThrownBy(() -> fixture().autonomy()
                .adjustAll(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjustmentsLimitedByStep() {
        Fixture fixture = fixture();
        List<RegionAdjustment> results =
                fixture.autonomy().adjustAll(Map.of(
                        "r1", 200L));
        assertThat(results.get(0).applied()).isTrue();
        assertThat(fixture.quota().quota("r1")).isEqualTo(75);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedRegionCounts(int count) {
        AutonomousTrafficController controller =
                controller(count);
        GlobalTrafficAutonomy autonomy =
                new GlobalTrafficAutonomy(controller,
                        regions(count));
        Map<String, Long> targets = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            targets.put("r" + (i + 1), 80L);
        }
        List<RegionAdjustment> results =
                autonomy.adjustAll(targets);
        assertThat(results).hasSize(count);
        assertThat(results).allMatch(RegionAdjustment::applied);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedAdjustRounds(int rounds) {
        Fixture fixture = fixture();
        for (int i = 0; i < rounds; i++) {
            fixture.autonomy().adjustAll(Map.of("r1", 80L));
        }
        assertThat(fixture.quota().quota("r1")).isBetween(10L, 200L);
    }

    @Test
    void emptyRegionsNoAdjustments() {
        AutonomousTrafficController controller =
                new AutonomousTrafficController(new RegionQuota(),
                        0.5, 10, 200);
        GlobalTrafficAutonomy autonomy =
                new GlobalTrafficAutonomy(controller, List.of());
        assertThat(autonomy.adjustAll(Map.of("r1", 80L))).isEmpty();
    }

    @Test
    void concurrentAdjustAllStable() throws Exception {
        Fixture fixture = fixture();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    fixture.autonomy().adjustAll(Map.of(
                            "r1", 80L));
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
    void reasonCarriedOnRejected() {
        Fixture fixture = fixture();
        fixture.controller().openCircuit("overload");
        List<RegionAdjustment> results =
                fixture.autonomy().adjustAll(Map.of("r1", 80L));
        assertThat(results.get(0).applied()).isFalse();
        assertThat(results.get(0).reason()).contains("circuit open");
    }

    private static Fixture fixture() {
        AutonomousTrafficController controller = controller(2);
        return new Fixture(controller,
                new GlobalTrafficAutonomy(controller,
                        regions(2)));
    }

    private static AutonomousTrafficController controller(int count) {
        RegionQuota quota = new RegionQuota();
        for (int i = 0; i < count; i++) {
            quota.setQuota("r" + (i + 1), 50);
        }
        return new AutonomousTrafficController(quota, 0.5, 10, 200);
    }

    private static List<String> regions(int count) {
        List<String> regions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add("r" + (i + 1));
        }
        return regions;
    }

    private record Fixture(AutonomousTrafficController controller,
                           GlobalTrafficAutonomy autonomy) {
        RegionQuota quota() {
            return controller.quota();
        }
    }
}
