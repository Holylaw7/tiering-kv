package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.SelfLearningFence.Adjustment;
import io.tieringkv.capacity.ai.SelfLearningFence.Bounds;
import io.tieringkv.capacity.ai.SelfLearningFence.Params;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自学习围栏（ADR-0165）：成功放宽/失败收紧/回滚熔断。 */
class SelfLearningFenceTest {

    @Test
    void successThresholdTriggersRelax() {
        SelfLearningFence fence = fence();
        fence.recordSuccess();
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(11);
        assertThat(fence.params().maxStepNodes()).isEqualTo(6);
        assertThat(fence.params().maxRegionsAffected()).isEqualTo(6);
    }

    @Test
    void belowThresholdNoChange() {
        SelfLearningFence fence = fence();
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(10);
        assertThat(fence.consecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void failureThresholdTriggersTighten() {
        SelfLearningFence fence = fence();
        fence.recordFailure("prewrite failed");
        fence.recordFailure("prewrite failed");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(9);
        assertThat(fence.params().maxStepNodes()).isEqualTo(4);
        assertThat(fence.params().maxRegionsAffected()).isEqualTo(4);
    }

    @Test
    void failureResetsSuccessStreak() {
        SelfLearningFence fence = fence();
        fence.recordSuccess();
        fence.recordFailure("x");
        assertThat(fence.consecutiveSuccesses()).isZero();
        assertThat(fence.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void rollbackOpensCircuit() {
        SelfLearningFence fence = fence();
        fence.recordRollback("migration failed");
        assertThat(fence.circuitOpen()).isTrue();
        assertThat(fence.audit()).isNotEmpty();
    }

    @Test
    void resetCircuitRestores() {
        SelfLearningFence fence = fence();
        fence.recordRollback("x");
        fence.resetCircuit();
        assertThat(fence.circuitOpen()).isFalse();
    }

    @Test
    void paramsClampedToLowerBound() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(1, 1, 1),
                new Bounds(1, 20, 1, 10, 1, 8),
                1, 1, 1, 1);
        fence.recordFailure("x");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(1);
        assertThat(fence.params().maxStepNodes()).isEqualTo(1);
        assertThat(fence.params().maxRegionsAffected()).isEqualTo(1);
    }

    @Test
    void paramsClampedToUpperBound() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(19, 9, 7),
                new Bounds(1, 20, 1, 10, 1, 8),
                1, 1, 1, 1);
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(20);
        assertThat(fence.params().maxStepNodes()).isEqualTo(10);
        assertThat(fence.params().maxRegionsAffected()).isEqualTo(8);
    }

    @Test
    void auditTracksAdjustments() {
        SelfLearningFence fence = fence();
        fence.recordSuccess();
        fence.recordSuccess();
        assertThat(fence.audit()).hasSize(1);
        Adjustment adjustment = fence.audit().get(0);
        assertThat(adjustment.reason()).contains("relax");
        assertThat(adjustment.before().maxActionsPerDay())
                .isEqualTo(10);
        assertThat(adjustment.after().maxActionsPerDay())
                .isEqualTo(11);
    }

    @Test
    void invalidStepsRejected() {
        assertThatThrownBy(() -> new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidThresholdsRejected() {
        assertThatThrownBy(() -> new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidBoundsRejected() {
        assertThatThrownBy(() -> new Bounds(2, 1, 1, 10, 1, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bounds(0, 20, 1, 10, 1, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initialParamsClamped() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(99, 99, 99), bounds(), 1, 1, 1, 1);
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(20);
        assertThat(fence.params().maxStepNodes()).isEqualTo(10);
        assertThat(fence.params().maxRegionsAffected()).isEqualTo(8);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedSuccessThresholds(int threshold) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1,
                threshold, 2);
        for (int i = 0; i < threshold; i++) {
            fence.recordSuccess();
        }
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(11);
    }

    @ParameterizedTest(name = "failure threshold {0}")
    @ValueSource(ints = {1, 2, 5})
    void parameterizedFailureThresholds(int threshold) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1, 2,
                threshold);
        for (int i = 0; i < threshold; i++) {
            fence.recordFailure("x");
        }
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(9);
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 2, 5})
    void parameterizedRelaxSteps(int step) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), step, 1, 1, 1);
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(10 + step);
    }

    @Test
    void successAfterTightenRelaxes() {
        SelfLearningFence fence = fence();
        fence.recordFailure("x");
        fence.recordFailure("x");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(9);
        fence.recordSuccess();
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(10);
    }

    @Test
    void rollbackResetsCounters() {
        SelfLearningFence fence = fence();
        fence.recordSuccess();
        fence.recordRollback("x");
        assertThat(fence.consecutiveSuccesses()).isZero();
        assertThat(fence.consecutiveFailures()).isZero();
    }

    @Test
    void concurrentRecordsSerialized() throws Exception {
        SelfLearningFence fence = fence();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    fence.recordSuccess();
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
        SelfLearningFence fence = fence();
        fence.recordRollback("x");
        fence.recordSuccess();
        assertThat(fence.circuitOpen()).isTrue();
        fence.resetCircuit();
        fence.recordSuccess();
        assertThat(fence.circuitOpen()).isFalse();
    }

    private static SelfLearningFence fence() {
        return new SelfLearningFence(new Params(10, 5, 5),
                bounds(), 1, 1, 2, 2);
    }

    private static Bounds bounds() {
        return new Bounds(1, 20, 1, 10, 1, 8);
    }
}
