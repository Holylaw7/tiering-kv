package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.FederatedPushdownLearning
        .ModelSnapshot;
import io.tieringkv.sql.coprocessor.FederatedPushdownLearning
        .PrivacyStats;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent
        .Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RL 多智能体联邦学习（ADR-0257）：FedAvg + 裁剪 + 噪声 + 语义一致。 */
class FederatedPushdownLearningTest {

    private FederatedPushdownLearning learning(
            double noiseScale, double clipBound) {
        return new FederatedPushdownLearning(noiseScale,
                clipBound, new Random(42));
    }

    private void register(FederatedPushdownLearning learning,
                          int agentCount) {
        for (int i = 0; i < agentCount; i++) {
            learning.registerAgent("q" + i,
                    new ReinforcementPushdownAgent(1.0, 0.0,
                            100.0), 1.0);
        }
    }

    @Test
    void registerAndAggregateProducesSnapshot() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 2);
        ModelSnapshot snapshot = learning.aggregate("q0");
        assertThat(snapshot.q()).containsKeys(
                Action.PUSHDOWN, Action.KEEP_LOCAL);
        assertThat(snapshot.samples()).isEqualTo(2);
    }

    @Test
    void oversizedRewardIsClipped() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 1);
        learning.federatedLearn("q0", Action.PUSHDOWN, 100);
        PrivacyStats stats = learning.privacyStats();
        assertThat(stats.clippedUpdates()).isEqualTo(1);
        assertThat(learning.aggregated("q0").q()
                .get(Action.PUSHDOWN)).isEqualTo(1.0);
    }

    @Test
    void noiseIsInjectedWhenEnabled() {
        FederatedPushdownLearning learning = learning(1, 10);
        register(learning, 2);
        learning.federatedLearn("q0", Action.PUSHDOWN, 5);
        assertThat(learning.privacyStats().noisedUpdates())
                .isGreaterThan(0);
    }

    @Test
    void positiveRewardsDrivePushdownDecision() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 3);
        for (int i = 0; i < 5; i++) {
            learning.federatedLearn("q0", Action.PUSHDOWN, 1);
        }
        assertThat(learning.federatedDecide("q0"))
                .isEqualTo(Action.PUSHDOWN);
    }

    @Test
    void coordinatorUsedBeforeAggregation() {
        FederatedPushdownLearning learning = learning(0, 1);
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("q0",
                new ReinforcementPushdownAgent(1.0, 0.0, 100),
                1.0);
        coordinator.learn("q0", Action.PUSHDOWN, 5);
        learning.attachCoordinator(coordinator);
        assertThat(learning.federatedDecide("q0"))
                .isEqualTo(Action.PUSHDOWN);
    }

    @Test
    void semanticConsistencyIsTracked() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 1);
        learning.checkSemantics("q0", true);
        learning.checkSemantics("q0", false);
        PrivacyStats stats = learning.privacyStats();
        assertThat(stats.consistencyChecks()).isEqualTo(2);
        assertThat(stats.consistentChecks()).isEqualTo(1);
    }

    @Test
    void roundsIncrementOnLearn() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 1);
        learning.federatedLearn("q0", Action.PUSHDOWN, 1);
        assertThat(learning.rounds()).isEqualTo(1);
    }

    @Test
    void unknownQueryTypeRejected() {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 1);
        assertThatThrownBy(() -> learning.aggregate("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "agents={0} rounds={1}")
    @MethodSource("convergenceMatrix")
    void fedAvgConvergesToPushdown(int agentCount, int rounds) {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, agentCount);
        for (int i = 0; i < rounds; i++) {
            for (int j = 0; j < agentCount; j++) {
                learning.federatedLearn("q" + j,
                        Action.PUSHDOWN, 1);
            }
        }
        ModelSnapshot snapshot = learning.aggregated("q0");
        assertThat(snapshot.q().get(Action.PUSHDOWN))
                .isGreaterThan(
                        snapshot.q().get(Action.KEEP_LOCAL));
    }

    @ParameterizedTest(name = "noise={0} clip={1}")
    @MethodSource("noiseClipMatrix")
    void privacyControlsApply(double noiseScale,
                              double clipBound) {
        FederatedPushdownLearning learning = learning(noiseScale,
                clipBound);
        register(learning, 2);
        learning.federatedLearn("q0", Action.PUSHDOWN, 1000);
        PrivacyStats stats = learning.privacyStats();
        assertThat(stats.clippedUpdates()).isGreaterThan(0);
        if (noiseScale > 0) {
            assertThat(stats.noisedUpdates()).isGreaterThan(0);
        }
    }

    @ParameterizedTest(name = "semantic query={0} same={1} run={2}")
    @MethodSource("semanticsMatrix")
    void semanticsChecksAccumulate(String queryType, boolean same,
                                   int run) {
        FederatedPushdownLearning learning = learning(0, 1);
        register(learning, 5);
        for (int i = 0; i < run; i++) {
            learning.checkSemantics(queryType, same);
        }
        PrivacyStats stats = learning.privacyStats();
        assertThat(stats.consistencyChecks())
                .isEqualTo(run);
        assertThat(stats.consistentChecks())
                .isEqualTo(same ? run : 0);
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("validationMatrix")
    void invalidInputsRejected(String caseName) {
        assertThatThrownBy(() -> {
            switch (caseName) {
                case "null-query-type" -> learning(0, 1)
                        .registerAgent(null,
                                new ReinforcementPushdownAgent(
                                        1, 0, 1), 1);
                case "blank-query-type" -> learning(0, 1)
                        .registerAgent(" ",
                                new ReinforcementPushdownAgent(
                                        1, 0, 1), 1);
                case "zero-weight" -> learning(0, 1)
                        .registerAgent("q0",
                                new ReinforcementPushdownAgent(
                                        1, 0, 1), 0);
                case "negative-noise" -> new FederatedPushdownLearning(
                        -1, 1);
                case "zero-clip" -> new FederatedPushdownLearning(
                        0, 0);
                case "null-random" -> new FederatedPushdownLearning(
                        0, 1, null);
                case "null-action" -> {
                    FederatedPushdownLearning learning =
                            learning(0, 1);
                    register(learning, 1);
                    learning.federatedLearn("q0", null, 1);
                }
                case "null-coordinator" -> learning(0, 1)
                        .attachCoordinator(null);
                case "unknown-aggregate" -> {
                    FederatedPushdownLearning learning =
                            learning(0, 1);
                    register(learning, 1);
                    learning.aggregate("missing");
                }
                default -> throw new IllegalArgumentException(
                        "unknown case");
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> convergenceMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (int agents = 2; agents <= 5; agents++) {
            for (int rounds = 1; rounds <= 5; rounds++) {
                builder.add(Arguments.of(agents, rounds));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> noiseClipMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (double noise : new double[]{0, 0.1, 0.5, 1.0}) {
            for (double clip : new double[]{0.1, 1, 5, 10}) {
                builder.add(Arguments.of(noise, clip));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> semanticsMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (int q = 0; q < 5; q++) {
            for (boolean same : new boolean[]{true, false}) {
                for (int run = 1; run <= 3; run++) {
                    builder.add(Arguments.of("q" + q, same, run));
                }
            }
        }
        return builder.build();
    }

    static Stream<Arguments> validationMatrix() {
        return Stream.of("null-query-type", "blank-query-type",
                        "zero-weight", "negative-noise", "zero-clip",
                        "null-random", "null-action",
                        "null-coordinator", "unknown-aggregate")
                .map(Arguments::of);
    }
}
