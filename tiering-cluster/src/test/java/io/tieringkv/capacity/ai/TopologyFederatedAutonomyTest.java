package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 拓扑感知联邦自治（ADR-0193）：分组 → 分层聚合。 */
class TopologyFederatedAutonomyTest {

    @Test
    void registerWithGroups() {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        assertThat(autonomy.agentCount()).isEqualTo(4);
        assertThat(autonomy.regions()).hasSize(4);
    }

    @Test
    void duplicateRegionRejected() {
        TopologyFederatedAutonomy autonomy = autonomy(2, 1);
        assertThatThrownBy(() -> autonomy.registerRegion("r0",
                "g0", 0.1, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankGroupRejected() {
        assertThatThrownBy(() -> new TopologyFederatedAutonomy()
                .registerRegion("r0", "", 0.1, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRegionRejected() {
        assertThatThrownBy(() -> new TopologyFederatedAutonomy()
                .record("missing", Action.RELAX, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateUniformInitially() {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> Math.abs(w
                - 1.0 / 3) < 1e-9);
        assertThat(autonomy.audit()).hasSize(1);
    }

    @Test
    void groupMajorityShapesWeights() {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.TIGHTEN, 1.0);
            autonomy.record("r3", Action.TIGHTEN, 1.0);
        }
        Map<Action, Double> weights = autonomy.aggregate();
        // 组内平均后组间平均：两组一致，RELAX 与 TIGHTEN 均衡
        assertThat(Math.abs(weights.get(Action.RELAX)
                - weights.get(Action.TIGHTEN))).isLessThan(0.1);
    }

    @Test
    void dominantGroupWins() {
        TopologyFederatedAutonomy autonomy = new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g0", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r1", "g0", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r2", "g1", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r3", "g1", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r4", "g2", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r5", "g2", 0.5, 0.0, 10.0);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.TIGHTEN, 1.0);
            autonomy.record("r3", Action.TIGHTEN, 1.0);
            autonomy.record("r4", Action.TIGHTEN, 1.0);
            autonomy.record("r5", Action.TIGHTEN, 1.0);
        }
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.get(Action.TIGHTEN))
                .isGreaterThan(weights.get(Action.RELAX));
    }

    @Test
    void aggregateWeightsSumToOne() {
        TopologyFederatedAutonomy autonomy = autonomy(6, 3);
        for (int i = 0; i < 10; i++) {
            autonomy.record("r" + (i % 6), Action.MAINTAIN, 1.0);
        }
        double sum = autonomy.aggregate().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void auditRecordsGroupSizes() {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        autonomy.aggregate();
        var record = autonomy.audit().get(0);
        assertThat(record.groupSizes().values())
                .containsExactlyInAnyOrder(2L, 2L);
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 6, 10})
    void parameterizedAgentCounts(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(count, 2);
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r" + (i % 4), Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(autonomy.aggregate()
                        .get(Action.TIGHTEN));
    }

    @Test
    void concurrentRecordsAndAggregate() throws Exception {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                autonomy.record("r" + (i % 4),
                        Action.RELAX, 1.0);
            }
        });
        Thread aggregator = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                autonomy.aggregate();
            }
        });
        writer.start();
        aggregator.start();
        writer.join(10_000);
        aggregator.join(10_000);
        assertThat(autonomy.audit()).hasSize(20);
    }

    private static TopologyFederatedAutonomy autonomy(
            int agents, int groups) {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        for (int i = 0; i < agents; i++) {
            autonomy.registerRegion("r" + i,
                    "g" + (i % groups), 0.5, 0.0, 10.0);
        }
        return autonomy;
    }
}
