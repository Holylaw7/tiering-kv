package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor.Advice;
import io.tieringkv.capacity.ai.AutoCapacityAdvisor.RiskLevel;
import io.tieringkv.capacity.ai.AutonomousCapacityController.Adjustment;
import io.tieringkv.capacity.ai.AutonomousCapacityController.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 容量自治控制器（ADR-0151）：护栏矩阵 + 幂等 + 失败登记。 */
class AutonomousCapacityControllerTest {

    @Test
    void applyWithinStepExecuted() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 3, 5, 100);
        Adjustment adjustment = controller.apply(advice(4));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(controller.currentNodes()).isEqualTo(4);
    }

    @Test
    void applyNoChangeSkipped() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 3, 5, 100);
        Adjustment adjustment = controller.apply(advice(2));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(controller.currentNodes()).isEqualTo(2);
    }

    @Test
    void applyOverStepRejected() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 3, 5, 100);
        Adjustment adjustment = controller.apply(advice(10));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(controller.currentNodes()).isEqualTo(2);
        assertThat(controller.rejectedReasons()).isNotEmpty();
    }

    @Test
    void dailyLimitRejected() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 2, 100);
        assertThat(controller.apply(advice(4)).outcome())
                .isEqualTo(Outcome.EXECUTED);
        assertThat(controller.apply(advice(6)).outcome())
                .isEqualTo(Outcome.EXECUTED);
        assertThat(controller.apply(advice(8)).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(controller.adjustmentsToday()).isEqualTo(2);
    }

    @Test
    void highWatermarkRejected() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(10, 5, 10, 12);
        Adjustment adjustment = controller.apply(advice(14));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(controller.currentNodes()).isEqualTo(10);
    }

    @Test
    void newDayResetsAdjustmentCount() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 1, 100);
        controller.apply(advice(4));
        assertThat(controller.adjustmentsToday()).isEqualTo(1);
        controller.newDay();
        assertThat(controller.adjustmentsToday()).isZero();
        assertThat(controller.apply(advice(6)).outcome())
                .isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void scaleDownAllowed() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(10, 2, 5, 100);
        Adjustment adjustment = controller.apply(advice(5));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(controller.currentNodes()).isEqualTo(5);
    }

    @Test
    void scaleDownAtHighWatermarkAllowed() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(12, 5, 10, 12);
        assertThat(controller.apply(advice(8)).outcome())
                .isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void rejectedReasonsAccumulate() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 1, 5, 100);
        controller.apply(advice(10));
        controller.apply(advice(20));
        assertThat(controller.rejectedReasons()).hasSize(2);
    }

    @Test
    void invalidLimitsRejected() {
        assertThatThrownBy(() -> new AutonomousCapacityController(
                0, 3, 5, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousCapacityController(
                2, 0, 5, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousCapacityController(
                2, 3, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousCapacityController(
                2, 3, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAdviceRejected() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 3, 5, 100);
        assertThatThrownBy(() -> controller.apply(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetClampedToAtLeastOneNode() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 5, 100);
        Adjustment adjustment = controller.apply(
                advice(0));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(controller.currentNodes()).isEqualTo(1);
    }

    @Test
    void idempotentReapplySkipped() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 5, 100);
        controller.apply(advice(4));
        assertThat(controller.apply(advice(4)).outcome())
                .isEqualTo(Outcome.SKIPPED);
        assertThat(controller.adjustmentsToday()).isEqualTo(1);
    }

    @Test
    void concurrentAppliesSerialized() throws Exception {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 1, 100, 100);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    controller.apply(advice(3));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(controller.currentNodes()).isEqualTo(3);
        assertThat(controller.adjustmentsToday()).isEqualTo(1);
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedStepLimits(int maxStep) {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(10, maxStep, 10, 100);
        Adjustment adjustment = controller.apply(
                advice(10 + maxStep));
        assertThat(adjustment.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(controller.apply(
                advice(10 + 2 * maxStep + 1))
                .outcome()).isEqualTo(Outcome.REJECTED);
    }

    @ParameterizedTest(name = "daily {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedDailyLimits(int dailyLimit) {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 1, dailyLimit, 100);
        for (int i = 0; i < dailyLimit; i++) {
            assertThat(controller.apply(advice(3 + i)).outcome())
                    .isEqualTo(Outcome.EXECUTED);
        }
        assertThat(controller.apply(advice(50)).outcome())
                .isEqualTo(Outcome.REJECTED);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(ints = {1, 5, 50})
    void parameterizedTargets(int target) {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 100, 10, 100);
        assertThat(controller.apply(advice(target)).outcome())
                .isEqualTo(Outcome.EXECUTED);
        assertThat(controller.currentNodes()).isEqualTo(
                Math.max(1, target));
    }

    @ParameterizedTest(name = "watermark {0}")
    @CsvSource({"5,true", "100,false"})
    void parameterizedWatermarks(int watermark, boolean rejected) {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(10, 20, 10, watermark);
        Adjustment adjustment = controller.apply(advice(15));
        assertThat(adjustment.outcome())
                .isEqualTo(rejected ? Outcome.REJECTED
                        : Outcome.EXECUTED);
    }

    @Test
    void executedAdjustmentRecordsReasonEmpty() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 5, 5, 100);
        Adjustment adjustment = controller.apply(advice(4));
        assertThat(adjustment.reason()).isEmpty();
        assertThat(adjustment.currentNodes()).isEqualTo(4);
        assertThat(adjustment.targetNodes()).isEqualTo(4);
    }

    @Test
    void multipleDaysResetCounters() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 1, 100);
        controller.apply(advice(4));
        controller.newDay();
        controller.apply(advice(6));
        controller.newDay();
        controller.apply(advice(8));
        assertThat(controller.currentNodes()).isEqualTo(8);
        assertThat(controller.adjustmentsToday()).isEqualTo(1);
    }

    private static Advice advice(int nodes) {
        return new Advice("qps", 100, 200, nodes, 2,
                RiskLevel.LOW, 0.9);
    }
}
