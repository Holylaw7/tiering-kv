package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.OnlineParetoRebalancer.Rebalance;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在线 Pareto 重平衡（ADR-0198）：前沿更新 + 限幅 + 幂等。 */
class OnlineParetoRebalancerTest {

    @Test
    void rebalanceWithinLimitRecommends() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = candidate("current", 10, 0.5, 0.5,
                0.5);
        Rebalance result = rebalancer.rebalance(List.of(
                current, candidate("better", 12, 0.9, 0.1, 0.1)),
                current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
        assertThat(result.frontSize()).isEqualTo(1);
    }

    @Test
    void rebalanceOverLimitKeepsCurrent() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(1);
        Candidate current = candidate("current", 10, 0.5, 0.5,
                0.5);
        Rebalance result = rebalancer.rebalance(List.of(
                current, candidate("better", 20, 0.9, 0.1, 0.1)),
                current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("current");
    }

    @Test
    void historyAccumulates() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = candidate("c", 10, 0.5, 0.5, 0.5);
        rebalancer.rebalance(List.of(current), current, 1, 1, 1);
        rebalancer.rebalance(List.of(current), current, 1, 1, 1);
        assertThat(rebalancer.history()).hasSize(2);
        assertThat(rebalancer.history().get(1).round()).isEqualTo(1);
    }

    @Test
    void emptyCandidatesRejected() {
        assertThatThrownBy(() -> new OnlineParetoRebalancer(5)
                .rebalance(List.of(), candidate("c", 1, 0.5, 0.5,
                        0.5), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCurrentRejected() {
        assertThatThrownBy(() -> new OnlineParetoRebalancer(5)
                .rebalance(List.of(candidate("c", 1, 0.5, 0.5,
                        0.5)), null, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidLimitRejected() {
        assertThatThrownBy(() -> new OnlineParetoRebalancer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 5, 50})
    void parameterizedLimits(int limit) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = candidate("current", 10, 0.5, 0.5,
                0.5);
        Rebalance result = rebalancer.rebalance(List.of(
                current, candidate("better", 10 + limit, 0.9,
                        0.1, 0.1)), current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = candidate("c", 10, 0.5, 0.5, 0.5);
        for (int i = 0; i < rounds; i++) {
            rebalancer.rebalance(List.of(current), current,
                    1, 1, 1);
        }
        assertThat(rebalancer.history()).hasSize(rounds);
    }

    @Test
    void concurrentRebalanceStable() throws Exception {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = candidate("c", 10, 0.5, 0.5, 0.5);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    rebalancer.rebalance(List.of(current),
                            current, 1, 1, 1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(rebalancer.history()).hasSize(200);
    }

    @Test
    void rebalanceIdempotent() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = candidate("c", 10, 0.5, 0.5, 0.5);
        List<Candidate> candidates = List.of(current);
        Rebalance first = rebalancer.rebalance(candidates,
                current, 1, 1, 1);
        Rebalance second = rebalancer.rebalance(candidates,
                current, 1, 1, 1);
        assertThat(second.recommended())
                .isEqualTo(first.recommended());
    }

    private static Candidate candidate(String name, int nodes,
                                       double slo, double cost,
                                       double risk) {
        return new Candidate(name, nodes, slo, cost, risk);
    }
}
