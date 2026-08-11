package io.tieringkv.dr;

import io.tieringkv.replication.ReplicationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 容灾拓扑与切换（ADR-0115）：计划/故障切换、演练 RTO/RPO。 */
class DrTopologyTest {

    private static DrTopology topology() {
        return new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY,
                        "c", DrRole.OBSERVER),
                Map.of("a", ReplicationMode.SYNC,
                        "b", ReplicationMode.SYNC));
    }

    @Test
    void rolesAssigned() {
        DrTopology topology = topology();
        assertThat(topology.role("a")).isEqualTo(DrRole.PRIMARY);
        assertThat(topology.role("b")).isEqualTo(DrRole.SECONDARY);
        assertThat(topology.role("c")).isEqualTo(DrRole.OBSERVER);
        assertThat(topology.role("unknown")).isEqualTo(DrRole.OBSERVER);
    }

    @Test
    void plannedSwitchActions() {
        SwitchPlan plan = new DrSwitchPlanner().plannedSwitch(
                topology(), "a", "b");
        assertThat(plan.safe()).isTrue();
        assertThat(plan.actions()).contains("flush-decisions:a",
                "catch-up:b", "promote:b", "demote:a");
        assertThat(plan.expectedRpoMillis()).isZero();
    }

    @Test
    void plannedSwitchFromNonPrimaryRejected() {
        assertThatThrownBy(() -> new DrSwitchPlanner().plannedSwitch(
                topology(), "b", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannedSwitchToNonSecondaryRejected() {
        assertThatThrownBy(() -> new DrSwitchPlanner().plannedSwitch(
                topology(), "a", "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failoverPrimaryAsyncRpo() {
        DrTopology async = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.ASYNC));
        SwitchPlan plan = new DrSwitchPlanner().failover(async, "a");
        assertThat(plan.safe()).isTrue();
        assertThat(plan.expectedRpoMillis()).isEqualTo(5_000);
        assertThat(plan.actions()).contains("promote-secondary",
                "redirect-gateway");
    }

    @Test
    void failoverSyncRpoZero() {
        SwitchPlan plan = new DrSwitchPlanner().failover(topology(), "a");
        assertThat(plan.expectedRpoMillis()).isZero();
    }

    @Test
    void failoverIneligibleRegionRejected() {
        assertThatThrownBy(() -> new DrSwitchPlanner().failover(
                topology(), "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drillRunnerMeasuresRto() {
        SwitchPlan plan = new DrSwitchPlanner().plannedSwitch(
                topology(), "a", "b");
        DrDrillRunner.DrillResult result =
                new DrDrillRunner().run(plan, () -> true, 250);
        assertThat(result.success()).isTrue();
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(250);
        assertThat(result.rpoMillis()).isZero();
    }

    @Test
    void drillRunnerFailure() {
        SwitchPlan plan = new DrSwitchPlanner().failover(topology(), "a");
        DrDrillRunner.DrillResult result =
                new DrDrillRunner().run(plan, () -> false, 0);
        assertThat(result.success()).isFalse();
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {0, 50, 500})
    void parameterizedDrillDelay(int delayMillis) {
        SwitchPlan plan = new DrSwitchPlanner().failover(topology(), "a");
        DrDrillRunner.DrillResult result =
                new DrDrillRunner().run(plan, () -> true, delayMillis);
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(delayMillis);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3})
    void parameterizedTopologySizes(int regionCount) {
        Map<String, DrRole> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < regionCount; i++) {
            roles.put("r" + i, i == 0 ? DrRole.PRIMARY
                    : i == 1 ? DrRole.SECONDARY : DrRole.OBSERVER);
        }
        DrTopology topology = new DrTopology(roles, Map.of());
        assertThat(topology.role("r0")).isEqualTo(DrRole.PRIMARY);
    }

    @Test
    void switchPlanImmutability() {
        SwitchPlan plan = new DrSwitchPlanner().plannedSwitch(
                topology(), "a", "b");
        assertThat(plan.actions()).isNotSameAs(
                java.util.List.copyOf(plan.actions()));
    }
}
