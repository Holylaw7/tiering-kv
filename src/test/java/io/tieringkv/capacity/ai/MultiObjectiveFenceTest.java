package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.MultiObjectiveFence.Adjustment;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Bounds;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Feedback;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Params;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Weights;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多目标自学习围栏（ADR-0172）：加权评分 + 参数调整。 */
class MultiObjectiveFenceTest {

    @Test
    void highScoreRelaxes() {
        MultiObjectiveFence fence = fence();
        Adjustment adjustment = fence.record(feedback(1.0, 0.0, 1.0));
        assertThat(adjustment.reason()).isEqualTo("relax");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(11);
    }

    @Test
    void lowScoreTightens() {
        MultiObjectiveFence fence = fence();
        Adjustment adjustment = fence.record(feedback(0.0, 1.0, 0.0));
        assertThat(adjustment.reason()).isEqualTo("tighten");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(9);
    }

    @Test
    void middleScoreMaintains() {
        MultiObjectiveFence fence = fence();
        Adjustment adjustment = fence.record(feedback(0.5, 0.5, 0.5));
        assertThat(adjustment.reason()).isEqualTo("maintain");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(10);
    }

    @Test
    void scoreWeighted() {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(), new Weights(1, 0, 0),
                0.8, 0.2, 1, 1);
        assertThat(fence.score(feedback(1.0, 1.0, 0.0)))
                .isEqualTo(1.0);
    }

    @Test
    void riskWeightReducesScoreOnFailure() {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(), new Weights(1, 1, 0),
                0.8, 0.2, 1, 1);
        assertThat(fence.score(feedback(1.0, 1.0, 0.0)))
                .isEqualTo(0.5);
    }

    @Test
    void rollbackOpensCircuit() {
        MultiObjectiveFence fence = fence();
        fence.recordRollback("migration failed");
        assertThat(fence.circuitOpen()).isTrue();
        assertThat(fence.audit()).isNotEmpty();
    }

    @Test
    void resetCircuitRestores() {
        MultiObjectiveFence fence = fence();
        fence.recordRollback("x");
        fence.resetCircuit();
        assertThat(fence.circuitOpen()).isFalse();
    }

    @Test
    void paramsClampedToBounds() {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(1, 1, 1),
                new Bounds(1, 20, 1, 10, 1, 8),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
        fence.record(feedback(0.0, 1.0, 0.0));
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(1);
        fence.record(feedback(1.0, 0.0, 1.0));
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(2);
    }

    @Test
    void auditTracksAdjustments() {
        MultiObjectiveFence fence = fence();
        fence.record(feedback(1.0, 0.0, 1.0));
        assertThat(fence.audit()).hasSize(1);
        Adjustment adjustment = fence.audit().get(0);
        assertThat(adjustment.before().maxActionsPerDay())
                .isEqualTo(10);
        assertThat(adjustment.after().maxActionsPerDay())
                .isEqualTo(11);
        assertThat(adjustment.score()).isEqualTo(1.0);
    }

    @Test
    void invalidThresholdsRejected() {
        assertThatThrownBy(() -> new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), 0.5, 0.5, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), 0.8, 0.9, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidStepsRejected() {
        assertThatThrownBy(() -> new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), 0.8, 0.2, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroWeightsRejected() {
        assertThatThrownBy(() -> new Weights(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeWeightsRejected() {
        assertThatThrownBy(() -> new Weights(-1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidFeedbackRejected() {
        assertThatThrownBy(() -> feedback(1.5, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> feedback(0, -0.1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFeedbackRejected() {
        MultiObjectiveFence fence = fence();
        assertThatThrownBy(() -> fence.record(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fence.score(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "weights {0}")
    @ValueSource(strings = {"cost", "risk", "slo"})
    void parameterizedWeightDominance(String dominant) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                switch (dominant) {
                    case "cost" -> new Weights(1, 0, 0);
                    case "risk" -> new Weights(0, 1, 0);
                    default -> new Weights(0, 0, 1);
                }, 0.8, 0.2, 1, 1);
        Feedback good = feedback(1.0, 0.0, 1.0);
        assertThat(fence.score(good)).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "score {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.7, 1.0})
    void parameterizedScoreBehavior(double costSaving) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
        Feedback feedback = new Feedback(costSaving, 0, 0);
        Adjustment adjustment = fence.record(feedback);
        double score = fence.score(feedback);
        if (score >= 0.8) {
            assertThat(adjustment.reason()).isEqualTo("relax");
        } else if (score <= 0.2) {
            assertThat(adjustment.reason()).isEqualTo("tighten");
        } else {
            assertThat(adjustment.reason()).isEqualTo("maintain");
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedFeedbackRounds(int rounds) {
        MultiObjectiveFence fence = fence();
        for (int i = 0; i < rounds; i++) {
            fence.record(feedback(i % 3 == 0 ? 0.0 : 1.0,
                    i % 3 == 0 || i % 3 == 1 ? 1.0 : 0.0,
                    i % 3 == 2 ? 1.0 : 0.0));
        }
        assertThat(fence.audit()).isNotEmpty();
    }

    @Test
    void concurrentRecordsSerialized() throws Exception {
        MultiObjectiveFence fence = fence();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    fence.record(feedback(1.0, 0.0, 1.0));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(20);
        assertThat(fence.audit()).isNotEmpty();
    }

    @Test
    void circuitStaysOpenUntilReset() {
        MultiObjectiveFence fence = fence();
        fence.recordRollback("x");
        fence.record(feedback(1.0, 0.0, 1.0));
        assertThat(fence.circuitOpen()).isTrue();
        fence.resetCircuit();
        assertThat(fence.circuitOpen()).isFalse();
    }

    private static MultiObjectiveFence fence() {
        return new MultiObjectiveFence(new Params(10, 5, 5),
                bounds(), new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
    }

    private static Bounds bounds() {
        return new Bounds(1, 20, 1, 10, 1, 8);
    }

    private static Feedback feedback(double cost, double failure,
                                     double slo) {
        return new Feedback(cost, failure, slo);
    }
}
