package io.tieringkv.cluster;

import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler.ScheduleResult;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.cluster.topology.TopologyDiscovery.Heartbeat;
import io.tieringkv.cluster.topology.TopologyDiscovery.NodeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自治调度 + 拓扑发现（ADR-0211）。 */
class AutonomyTopologyTest {

    @Test
    void executeWithinLimit() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                3);
        ScheduleResult result = scheduler.execute(
                new Move("n1", "n2", 10));
        assertThat(result.executed()).isTrue();
        assertThat(scheduler.movesThisRound()).isEqualTo(1);
    }

    @Test
    void roundLimitRejected() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1);
        scheduler.execute(new Move("n1", "n2", 10));
        ScheduleResult result = scheduler.execute(
                new Move("n2", "n3", 10));
        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("round limit");
    }

    @Test
    void circuitOpenRejects() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                5);
        scheduler.openCircuit("failure");
        ScheduleResult result = scheduler.execute(
                new Move("n1", "n2", 10));
        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("circuit open");
    }

    @Test
    void resetCircuitRestores() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                5);
        scheduler.openCircuit("x");
        scheduler.resetCircuit();
        assertThat(scheduler.execute(
                new Move("n1", "n2", 10)).executed()).isTrue();
    }

    @Test
    void newRoundResetsCount() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1);
        scheduler.execute(new Move("n1", "n2", 10));
        scheduler.newRound();
        assertThat(scheduler.execute(
                new Move("n2", "n3", 10)).executed()).isTrue();
    }

    @Test
    void executedMovesTracked() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                3);
        scheduler.execute(new Move("n1", "n2", 10));
        assertThat(scheduler.executedMoves())
                .containsExactly("n1->n2:10");
    }

    @Test
    void nullMoveRejected() {
        assertThatThrownBy(() -> new AutonomousPdScheduler(1)
                .execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidLimitRejected() {
        assertThatThrownBy(() -> new AutonomousPdScheduler(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heartbeatRegistersHealthyNode() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 100),
                500);
        NodeInfo node = discovery.nodes().get(0);
        assertThat(node.healthy()).isTrue();
        assertThat(node.region()).isEqualTo("r1");
    }

    @Test
    void staleHeartbeatUnhealthy() {
        TopologyDiscovery discovery = new TopologyDiscovery(100);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 100),
                500);
        assertThat(discovery.nodes().get(0).healthy()).isFalse();
    }

    @Test
    void groupByRegion() {
        TopologyDiscovery discovery = discovery();
        assertThat(discovery.groupByRegion())
                .containsKeys("r1", "r2");
        assertThat(discovery.groupByRegion().get("r1"))
                .containsExactlyInAnyOrder("n1", "n2");
    }

    @Test
    void groupByAz() {
        TopologyDiscovery discovery = discovery();
        assertThat(discovery.groupByAz()).containsKeys("az-1",
                "az-2");
    }

    @Test
    void removeNode() {
        TopologyDiscovery discovery = discovery();
        discovery.remove("n1");
        assertThat(discovery.size()).isEqualTo(2);
    }

    @Test
    void nullHeartbeatRejected() {
        assertThatThrownBy(() -> new TopologyDiscovery(1000)
                .heartbeat(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTimeoutRejected() {
        assertThatThrownBy(() -> new TopologyDiscovery(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedLimits(int limit) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                limit);
        int executed = 0;
        for (int i = 0; i < limit * 2; i++) {
            if (scheduler.execute(new Move("n" + i, "n" + (i + 1),
                    1)).executed()) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(limit);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedTopologyNodes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-" + (i % 3), 0), 500);
        }
        assertThat(discovery.size()).isEqualTo(count);
    }

    @Test
    void concurrentDiscoveryStable() throws Exception {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                discovery.heartbeat(new Heartbeat("n" + i,
                        "r" + (i % 3), "az-" + (i % 2), 0), 500);
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                discovery.groupByRegion();
                discovery.nodes();
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(discovery.size()).isEqualTo(100);
    }

    private static TopologyDiscovery discovery() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 0),
                500);
        discovery.heartbeat(new Heartbeat("n2", "r1", "az-1", 0),
                500);
        discovery.heartbeat(new Heartbeat("n3", "r2", "az-2", 0),
                500);
        return discovery;
    }
}
