package io.tieringkv.dr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 五中心混沌（Goal 8）：故障矩阵与全球读。 */
class FiveRegionChaosTest {

    private static DrTopology five() {
        return FiveRegionTopology.of("a", "b", "c", "d", "e");
    }

    @Test
    void singlePrimaryFailure() {
        SwitchPlan plan = new DrSwitchPlanner().failover(five(), "a");
        assertThat(plan.safe()).isTrue();
        assertThat(plan.actions()).contains("promote-secondary");
    }

    @ParameterizedTest(name = "region {0}")
    @ValueSource(strings = {"a", "b", "c", "d"})
    void anyNonArbiterFailure(String region) {
        SwitchPlan plan = new DrSwitchPlanner().failover(five(), region);
        assertThat(plan.safe()).isTrue();
    }

    @Test
    void arbiterLossRejected() {
        assertThatThrownBy(() -> new DrSwitchPlanner().failover(
                five(), "e")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void bothPrimariesFailover() {
        SwitchPlan first = new DrSwitchPlanner().failover(five(), "a");
        assertThat(first.safe()).isTrue();
        SwitchPlan second = new DrSwitchPlanner().failover(five(), "b");
        assertThat(second.safe()).isTrue();
    }

    @Test
    void globalReadStrongAfterFailover() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("c", 500L), region -> 500L,
                ConsistencyMode.STRONG);
        assertThat(router.route("c", 500)).isEqualTo("c");
    }

    @Test
    void globalReadBoundedStaleness() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("c", 500L), region -> 300L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("c", 400)).isEqualTo("c");
        assertThat(router.route("c", 600)).isNull();
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 250, 500})
    void globalReadBoundary(long seq) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("c", 500L), region -> 500L,
                ConsistencyMode.STRONG);
        assertThat(router.route("c", seq)).isEqualTo("c");
    }

    @Test
    void drillAfterFailover() {
        SwitchPlan plan = new DrSwitchPlanner().failover(five(), "a");
        DrDrillRunner.DrillResult result =
                new DrDrillRunner().run(plan, () -> true, 150);
        assertThat(result.success()).isTrue();
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(150);
    }
}
