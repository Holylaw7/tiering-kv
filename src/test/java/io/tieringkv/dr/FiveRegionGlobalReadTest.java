package io.tieringkv.dr;

import io.tieringkv.replication.ReplicationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三地五中心与全球读（ADR-0123）。 */
class FiveRegionGlobalReadTest {

    @Test
    void fiveRegionTopologyRoles() {
        DrTopology topology = FiveRegionTopology.of(
                "a", "b", "c", "d", "e");
        assertThat(topology.role("a")).isEqualTo(DrRole.PRIMARY);
        assertThat(topology.role("b")).isEqualTo(DrRole.PRIMARY);
        assertThat(topology.role("c")).isEqualTo(DrRole.SECONDARY);
        assertThat(topology.role("d")).isEqualTo(DrRole.SECONDARY);
        assertThat(topology.role("e")).isEqualTo(DrRole.OBSERVER);
    }

    @Test
    void fiveRegionFailover() {
        DrTopology topology = FiveRegionTopology.of(
                "a", "b", "c", "d", "e");
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, "a");
        assertThat(plan.safe()).isTrue();
    }

    @ParameterizedTest(name = "region {0}")
    @ValueSource(strings = {"a", "b", "c", "d"})
    void parameterizedFiveRegionFailover(String region) {
        DrTopology topology = FiveRegionTopology.of(
                "a", "b", "c", "d", "e");
        SwitchPlan plan = new DrSwitchPlanner().failover(topology, region);
        assertThat(plan.safe()).isTrue();
    }

    @Test
    void arbiterNotFailoverEligible() {
        DrTopology topology = FiveRegionTopology.of(
                "a", "b", "c", "d", "e");
        assertThatThrownBy(() -> new DrSwitchPlanner().failover(
                topology, "e")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void strongReadRequiresLocalWatermark() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 50L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", 60)).isNull();
    }

    @Test
    void strongReadLocalFresh() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 80L,
                ConsistencyMode.STRONG);
        assertThat(router.route("a", 80)).isEqualTo("a");
    }

    @Test
    void boundedReadUsesReplicatedWatermark() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 50L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 90)).isEqualTo("a");
    }

    @Test
    void boundedReadStaleRejected() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 50L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 101)).isNull();
    }

    @Test
    void stalenessRecorded() {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> 0L, ConsistencyMode.BOUNDED);
        router.recordStaleness(250);
        assertThat(router.stalenessMillis()).isEqualTo(250);
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 50, Long.MAX_VALUE})
    void boundedReadSeqBoundaries(long requiredSeq) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", Long.MAX_VALUE), region -> 10L,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", requiredSeq))
                .isEqualTo("a");
    }

    @ParameterizedTest(name = "mode {0}")
    @ValueSource(strings = {"STRONG", "BOUNDED"})
    void parameterizedReadModes(String modeName) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 100L), region -> 100L,
                ConsistencyMode.valueOf(modeName));
        assertThat(router.route("a", 100)).isEqualTo("a");
    }

}
