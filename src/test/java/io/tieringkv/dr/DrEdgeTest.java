package io.tieringkv.dr;

import io.tieringkv.replication.ReplicationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 容灾边缘（ADR-0115）：拓扑组合与切换参数。 */
class DrEdgeTest {

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3, 5})
    void parameterizedTopologyRoles(int regions) {
        Map<String, DrRole> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < regions; i++) {
            roles.put("r" + i, i == 0 ? DrRole.PRIMARY
                    : i == 1 ? DrRole.SECONDARY : DrRole.OBSERVER);
        }
        DrTopology topology = new DrTopology(roles, Map.of());
        assertThat(topology.role("r0")).isEqualTo(DrRole.PRIMARY);
        assertThat(topology.role("r1")).isEqualTo(DrRole.SECONDARY);
        for (int i = 2; i < regions; i++) {
            assertThat(topology.role("r" + i))
                    .isEqualTo(DrRole.OBSERVER);
        }
    }

    @Test
    void failoverSecondaryPromotes() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, "a");
        assertThat(plan.actions()).contains("promote-secondary");
    }

    @Test
    void failoverSecondaryRegion() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, "b");
        assertThat(plan.safe()).isTrue();
    }

    @ParameterizedTest(name = "rpo {0}")
    @ValueSource(longs = {0, 1_000, 60_000})
    void parameterizedExpectedRpo(long rpo) {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", rpo == 0 ? ReplicationMode.SYNC
                        : ReplicationMode.ASYNC));
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, "a");
        assertThat(plan.expectedRpoMillis()).isEqualTo(
                rpo == 0 ? 0 : 5_000);
    }

    @Test
    void switchPlanActionsOrdered() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of());
        SwitchPlan plan = new DrSwitchPlanner().plannedSwitch(
                topology, "a", "b");
        assertThat(plan.actions().get(0)).isEqualTo("flush-decisions:a");
        assertThat(plan.actions().get(plan.actions().size() - 1))
                .isEqualTo("demote:a");
    }

    @Test
    void emptyTopologyObserverDefault() {
        DrTopology topology = new DrTopology(Map.of(), Map.of());
        assertThat(topology.role("anything"))
                .isEqualTo(DrRole.OBSERVER);
    }

    @Test
    void plannedSwitchMissingRegionRejected() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY), Map.of());
        assertThatThrownBy(() -> new DrSwitchPlanner().plannedSwitch(
                topology, "a", "ghost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {0, 25, 100})
    void parameterizedDrill(int delayMillis) {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, "a");
        DrDrillRunner.DrillResult result =
                new DrDrillRunner().run(plan, () -> true, delayMillis);
        assertThat(result.success()).isTrue();
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(delayMillis);
    }
}
