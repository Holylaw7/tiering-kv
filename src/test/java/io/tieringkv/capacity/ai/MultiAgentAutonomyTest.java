package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多智能体自治（ADR-0186）：本地 Q + 联邦聚合。 */
class MultiAgentAutonomyTest {

    @Test
    void registerRegions() {
        MultiAgentAutonomy autonomy = autonomy(3);
        assertThat(autonomy.agentCount()).isEqualTo(3);
        assertThat(autonomy.regions()).containsExactlyInAnyOrder(
                "r0", "r1", "r2");
    }

    @Test
    void duplicateRegionRejected() {
        MultiAgentAutonomy autonomy = autonomy(1);
        assertThatThrownBy(() -> autonomy.registerRegion(
                "r0", 0.1, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRegionRejected() {
        assertThatThrownBy(() -> new MultiAgentAutonomy()
                .registerRegion("", 0.1, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRegionRecordRejected() {
        assertThatThrownBy(() -> new MultiAgentAutonomy()
                .record("missing", Action.RELAX, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localQUpdatedIndependently() {
        MultiAgentAutonomy autonomy = autonomy(2);
        autonomy.record("r0", Action.RELAX, 1.0);
        autonomy.record("r1", Action.TIGHTEN, 1.0);
        assertThat(autonomy.q("r0", Action.RELAX))
                .isGreaterThan(autonomy.q("r0", Action.TIGHTEN));
        assertThat(autonomy.q("r1", Action.TIGHTEN))
                .isGreaterThan(autonomy.q("r1", Action.RELAX));
    }

    @Test
    void aggregateProducesUniformWeightsInitially() {
        MultiAgentAutonomy autonomy = autonomy(3);
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> Math.abs(w
                - 1.0 / 3) < 1e-9);
        assertThat(autonomy.audit()).hasSize(1);
    }

    @Test
    void aggregateReflectsSharedExperience() {
        MultiAgentAutonomy autonomy = autonomy(3);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.RELAX, 1.0);
        }
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
    }

    @Test
    void majorityExperienceShapesGlobalWeights() {
        MultiAgentAutonomy autonomy = autonomy(3);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.TIGHTEN, 1.0);
        }
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
    }

    @Test
    void aggregateWeightsSumToOne() {
        MultiAgentAutonomy autonomy = autonomy(4);
        for (int i = 0; i < 10; i++) {
            autonomy.record("r" + (i % 4), Action.MAINTAIN, 1.0);
        }
        double sum = autonomy.aggregate().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aggregateAuditAccumulates() {
        MultiAgentAutonomy autonomy = autonomy(2);
        autonomy.aggregate();
        autonomy.aggregate();
        autonomy.aggregate();
        assertThat(autonomy.audit()).hasSize(3);
        assertThat(autonomy.audit().get(0).round()).isZero();
        assertThat(autonomy.audit().get(2).round()).isEqualTo(2);
    }

    @Test
    void chooseUsesLocalAgent() {
        MultiAgentAutonomy autonomy = autonomy(1);
        autonomy.record("r0", Action.RELAX, 1.0);
        assertThat(autonomy.choose("r0"))
                .isEqualTo(Action.RELAX);
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedAgentCounts(int count) {
        MultiAgentAutonomy autonomy = autonomy(count);
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> Math.abs(w
                - 1.0 / 3) < 1e-9);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        MultiAgentAutonomy autonomy = autonomy(2);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r" + (i % 2), Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(autonomy.aggregate()
                        .get(Action.TIGHTEN));
    }

    @Test
    void concurrentRecordsAndAggregate() throws Exception {
        MultiAgentAutonomy autonomy = autonomy(4);
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

    private static MultiAgentAutonomy autonomy(int count) {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        for (int i = 0; i < count; i++) {
            autonomy.registerRegion("r" + i, 0.5, 0.0, 10.0);
        }
        return autonomy;
    }
}
