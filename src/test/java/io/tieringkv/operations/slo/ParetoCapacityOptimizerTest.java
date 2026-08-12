package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pareto 容量优化（ADR-0191）：支配 + 前沿 + 权重选择。 */
class ParetoCapacityOptimizerTest {

    private final ParetoCapacityOptimizer optimizer =
            new ParetoCapacityOptimizer();

    @Test
    void dominatesWhenStrictlyBetter() {
        Candidate a = candidate("a", 10, 0.9, 0.2, 0.1);
        Candidate b = candidate("b", 10, 0.8, 0.3, 0.2);
        assertThat(optimizer.dominates(a, b)).isTrue();
        assertThat(optimizer.dominates(b, a)).isFalse();
    }

    @Test
    void noDominanceOnTradeoff() {
        Candidate a = candidate("a", 10, 0.9, 0.5, 0.1);
        Candidate b = candidate("b", 10, 0.5, 0.1, 0.9);
        assertThat(optimizer.dominates(a, b)).isFalse();
        assertThat(optimizer.dominates(b, a)).isFalse();
    }

    @Test
    void equalCandidatesNoDominance() {
        Candidate a = candidate("a", 10, 0.8, 0.2, 0.2);
        Candidate b = candidate("b", 10, 0.8, 0.2, 0.2);
        assertThat(optimizer.dominates(a, b)).isFalse();
    }

    @Test
    void paretoFrontFiltersDominated() {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("best", 10, 0.9, 0.1, 0.1),
                candidate("mid", 10, 0.7, 0.3, 0.3),
                candidate("worst", 10, 0.5, 0.5, 0.5)));
        assertThat(front).extracting(Candidate::name)
                .containsExactly("best");
    }

    @Test
    void paretoFrontKeepsTradeoffs() {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("slo", 20, 0.9, 0.5, 0.1),
                candidate("cost", 5, 0.5, 0.1, 0.9)));
        assertThat(front).hasSize(2);
    }

    @Test
    void paretoFrontSingleCandidate() {
        assertThat(optimizer.paretoFront(List.of(
                candidate("only", 10, 0.8, 0.2, 0.2)))).hasSize(1);
    }

    @Test
    void chooseByWeightsPrefersSlo() {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("slo", 20, 0.9, 0.5, 0.1),
                candidate("cost", 5, 0.5, 0.1, 0.9)));
        Candidate chosen = optimizer.chooseByWeights(front,
                1.0, 0.0, 0.0);
        assertThat(chosen.name()).isEqualTo("slo");
    }

    @Test
    void chooseByWeightsPrefersCost() {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("slo", 20, 0.9, 0.5, 0.1),
                candidate("cost", 5, 0.5, 0.1, 0.9)));
        Candidate chosen = optimizer.chooseByWeights(front,
                0.0, 1.0, 0.0);
        assertThat(chosen.name()).isEqualTo("cost");
    }

    @Test
    void chooseByWeightsPrefersLowRisk() {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("risky", 20, 0.9, 0.5, 0.9),
                candidate("safe", 5, 0.5, 0.5, 0.1)));
        Candidate chosen = optimizer.chooseByWeights(front,
                0.0, 0.0, 1.0);
        assertThat(chosen.name()).isEqualTo("safe");
    }

    @Test
    void invalidCandidateRejected() {
        assertThatThrownBy(() -> candidate("x", 0, 0.5, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("x", 1, 1.5, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDominanceRejected() {
        assertThatThrownBy(() -> optimizer.dominates(null,
                candidate("a", 1, 0.5, 0.5, 0.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCandidatesRejected() {
        assertThatThrownBy(() -> optimizer.paretoFront(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> optimizer.chooseByWeights(
                List.of(), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeWeightsRejected() {
        assertThatThrownBy(() -> optimizer.chooseByWeights(
                List.of(candidate("a", 1, 0.5, 0.5, 0.5)),
                -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedCandidateCounts(int count) {
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(candidate("c" + i, 1 + i,
                    0.9 - i * 0.05, 0.1 + i * 0.05,
                    0.1 + i * 0.05));
        }
        List<Candidate> front = optimizer.paretoFront(candidates);
        assertThat(front).isNotEmpty();
    }

    @ParameterizedTest(name = "weight {0}")
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void parameterizedWeights(double weight) {
        List<Candidate> front = optimizer.paretoFront(List.of(
                candidate("a", 10, 0.9, 0.5, 0.1),
                candidate("b", 5, 0.5, 0.1, 0.9)));
        assertThat(optimizer.chooseByWeights(front,
                weight, weight, weight)).isNotNull();
    }

    @Test
    void concurrentOptimizationStable() throws Exception {
        List<Candidate> candidates = List.of(
                candidate("a", 10, 0.9, 0.2, 0.1),
                candidate("b", 5, 0.5, 0.1, 0.9));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(optimizer.paretoFront(candidates))
                            .hasSize(2);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static Candidate candidate(String name, int nodes,
                                       double slo, double cost,
                                       double risk) {
        return new Candidate(name, nodes, slo, cost, risk);
    }
}
