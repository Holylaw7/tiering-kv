package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 强化学习自治（ADR-0180）：Q 更新 + epsilon-greedy + softmax。 */
class ReinforcementAutonomyTest {

    @Test
    void initialWeightsUniform() {
        ReinforcementAutonomy autonomy = autonomy(0.1, 0.0);
        Map<Action, Double> weights = autonomy.weights();
        assertThat(weights.values()).allMatch(w -> Math.abs(w
                - 1.0 / 3) < 1e-9);
    }

    @Test
    void greedyChoosesBestAction() {
        ReinforcementAutonomy autonomy = autonomy(0.1, 0.0);
        autonomy.record(Action.RELAX, 1.0);
        assertThat(autonomy.chooseAction())
                .isEqualTo(Action.RELAX);
    }

    @Test
    void recordMovesQTowardReward() {
        ReinforcementAutonomy autonomy = autonomy(0.5, 0.0);
        autonomy.record(Action.TIGHTEN, 1.0);
        assertThat(autonomy.q(Action.TIGHTEN)).isEqualTo(0.5);
        autonomy.record(Action.TIGHTEN, 1.0);
        assertThat(autonomy.q(Action.TIGHTEN)).isEqualTo(0.75);
    }

    @Test
    void negativeRewardLowersQ() {
        ReinforcementAutonomy autonomy = autonomy(0.5, 0.0);
        autonomy.record(Action.RELAX, -1.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(-0.5);
    }

    @Test
    void highRewardActionWeightIncreases() {
        ReinforcementAutonomy autonomy = autonomy(0.5, 0.0);
        for (int i = 0; i < 20; i++) {
            autonomy.record(Action.RELAX, 1.0);
            autonomy.record(Action.TIGHTEN, -1.0);
        }
        assertThat(autonomy.weights().get(Action.RELAX))
                .isGreaterThan(autonomy.weights().get(Action.TIGHTEN));
    }

    @Test
    void weightsSumToOne() {
        ReinforcementAutonomy autonomy = autonomy(0.1, 0.1);
        for (int i = 0; i < 50; i++) {
            autonomy.record(autonomy.chooseAction(),
                    (i % 2 == 0) ? 1.0 : -1.0);
        }
        double sum = autonomy.weights().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void epsilonOneExplores() {
        ReinforcementAutonomy autonomy = autonomy(0.1, 1.0);
        int relax = 0;
        for (int i = 0; i < 1000; i++) {
            if (autonomy.chooseAction() == Action.RELAX) {
                relax++;
            }
        }
        assertThat(relax).isBetween(200, 800);
    }

    @Test
    void qClampedToBound() {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                1.0, 0.0, 2.0);
        autonomy.record(Action.RELAX, 10.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(2.0);
        autonomy.record(Action.RELAX, -10.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(-2.0);
    }

    @Test
    void nullActionRejected() {
        assertThatThrownBy(() -> autonomy(0.1, 0.0)
                .record(null, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidLearningRateRejected() {
        assertThatThrownBy(() -> new ReinforcementAutonomy(
                0, 0.1, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReinforcementAutonomy(
                1.5, 0.1, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidEpsilonRejected() {
        assertThatThrownBy(() -> new ReinforcementAutonomy(
                0.1, -0.1, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReinforcementAutonomy(
                0.1, 1.5, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidQBoundRejected() {
        assertThatThrownBy(() -> new ReinforcementAutonomy(
                0.1, 0.1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.5, 1.0})
    void parameterizedLearningRates(double rate) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                rate, 0.0, 10.0);
        autonomy.record(Action.RELAX, 1.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(rate);
    }

    @ParameterizedTest(name = "epsilon {0}")
    @ValueSource(doubles = {0.0, 0.3, 1.0})
    void parameterizedEpsilons(double epsilon) {
        ReinforcementAutonomy autonomy = autonomy(0.1, epsilon);
        assertThat(autonomy.chooseAction()).isNotNull();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        ReinforcementAutonomy autonomy = autonomy(0.1, 0.0);
        for (int i = 0; i < rounds; i++) {
            autonomy.record(Action.RELAX, 1.0);
        }
        assertThat(autonomy.q(Action.RELAX))
                .isBetween(0.0, 1.0);
    }

    @Test
    void seededRandomDeterministic() {
        ReinforcementAutonomy first = new ReinforcementAutonomy(
                0.1, 0.5, 1.0, new Random(42));
        ReinforcementAutonomy second = new ReinforcementAutonomy(
                0.1, 0.5, 1.0, new Random(42));
        for (int i = 0; i < 100; i++) {
            assertThat(first.chooseAction())
                    .isEqualTo(second.chooseAction());
        }
    }

    @Test
    void concurrentRecordsSafe() throws Exception {
        ReinforcementAutonomy autonomy = autonomy(0.1, 0.0);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    autonomy.record(Action.RELAX, 1.0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(autonomy.weights().get(Action.RELAX))
                .isGreaterThan(autonomy.weights()
                        .get(Action.TIGHTEN));
    }

    private static ReinforcementAutonomy autonomy(double rate,
                                                  double epsilon) {
        return new ReinforcementAutonomy(rate, epsilon, 10.0);
    }
}
