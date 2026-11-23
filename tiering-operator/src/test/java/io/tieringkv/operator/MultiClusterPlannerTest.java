package io.tieringkv.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多集群拓扑与复制计划（ADR-0322 M4）：连接/断开/校验。 */
class MultiClusterPlannerTest {

    private static final MultiClusterTopology.ReplicationEdge EDGE =
            new MultiClusterTopology.ReplicationEdge(
                    "cluster-a", "cluster-b", "r1");

    @Test
    void missingEdgePlansConnect() {
        MultiClusterTopology desired = new MultiClusterTopology(
                List.of("cluster-a", "cluster-b"),
                List.of(EDGE));
        MultiClusterPlanner planner = new MultiClusterPlanner();
        List<MultiClusterPlanner.ReplicationEdgeAction> actions =
                planner.plan(desired, List.of());
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(
                MultiClusterPlanner.ReplicationEdgeAction
                        .ActionType.CONNECT);
        assertThat(actions.get(0).edge()).isEqualTo(EDGE);
    }

    @Test
    void staleEdgePlansDisconnect() {
        MultiClusterTopology desired = new MultiClusterTopology(
                List.of("cluster-a", "cluster-b"), List.of());
        MultiClusterPlanner planner = new MultiClusterPlanner();
        List<MultiClusterPlanner.ReplicationEdgeAction> actions =
                planner.plan(desired, List.of(EDGE));
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(
                MultiClusterPlanner.ReplicationEdgeAction
                        .ActionType.DISCONNECT);
    }

    @Test
    void convergedTopologyNoops() {
        MultiClusterTopology desired = new MultiClusterTopology(
                List.of("cluster-a", "cluster-b"),
                List.of(EDGE));
        MultiClusterPlanner planner = new MultiClusterPlanner();
        List<MultiClusterPlanner.ReplicationEdgeAction> actions =
                planner.plan(desired, List.of(EDGE));
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(
                MultiClusterPlanner.ReplicationEdgeAction
                        .ActionType.NOOP);
    }

    @Test
    void mixedPlanConnectsAndDisconnects() {
        MultiClusterTopology.ReplicationEdge keep =
                new MultiClusterTopology.ReplicationEdge(
                        "cluster-a", "cluster-b", "r1");
        MultiClusterTopology.ReplicationEdge add =
                new MultiClusterTopology.ReplicationEdge(
                        "cluster-b", "cluster-c", "r2");
        MultiClusterTopology.ReplicationEdge drop =
                new MultiClusterTopology.ReplicationEdge(
                        "cluster-a", "cluster-c", "r3");
        MultiClusterTopology desired = new MultiClusterTopology(
                List.of("cluster-a", "cluster-b", "cluster-c"),
                List.of(keep, add));
        MultiClusterPlanner planner = new MultiClusterPlanner();
        List<MultiClusterPlanner.ReplicationEdgeAction> actions =
                planner.plan(desired, List.of(keep, drop));
        assertThat(actions).hasSize(2);
        assertThat(actions).anyMatch(action ->
                action.type() == MultiClusterPlanner
                        .ReplicationEdgeAction.ActionType.CONNECT
                        && action.edge().equals(add));
        assertThat(actions).anyMatch(action ->
                action.type() == MultiClusterPlanner
                        .ReplicationEdgeAction.ActionType.DISCONNECT
                        && action.edge().equals(drop));
    }

    @Test
    void emptyClustersRejected() {
        assertThatThrownBy(() -> new MultiClusterTopology(
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void edgeOutsideClustersRejected() {
        assertThatThrownBy(() -> new MultiClusterTopology(
                List.of("cluster-a"),
                List.of(EDGE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void selfEdgeRejected() {
        assertThatThrownBy(() -> new MultiClusterTopology(
                List.of("cluster-a"),
                List.of(new MultiClusterTopology.ReplicationEdge(
                        "cluster-a", "cluster-a", "r1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self");
    }

    @ParameterizedTest(name = "cluster {0}")
    @ValueSource(strings = {"", "  "})
    void blankClusterRejected(String cluster) {
        assertThatThrownBy(() -> new MultiClusterTopology(
                List.of(cluster), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
