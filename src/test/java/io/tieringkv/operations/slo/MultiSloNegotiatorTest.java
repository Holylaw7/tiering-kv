package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.MultiSloNegotiator.Action;
import io.tieringkv.operations.slo.MultiSloNegotiator.NegotiationPlan;
import io.tieringkv.operations.slo.MultiSloNegotiator.SloInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多 SLO 谈判（ADR-0177）：加权缺口 + 最差优先。 */
class MultiSloNegotiatorTest {

    private final MultiSloNegotiator negotiator =
            new MultiSloNegotiator();

    @Test
    void allCompliantMaintains() {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.95, 0.9, 1),
                input("b", 0.92, 0.9, 1)),
                10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        assertThat(plan.suggestedNodes()).isEqualTo(10);
        assertThat(plan.worstDeficit()).isZero();
    }

    @Test
    void singleDeficitScalesUp() {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.95, 0.9, 1),
                input("b", 0.8, 0.9, 1)),
                10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.worstSloId()).isEqualTo("b");
        assertThat(plan.suggestedNodes()).isGreaterThan(10);
    }

    @Test
    void worstSloPrioritized() {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.85, 0.9, 1),
                input("b", 0.3, 0.9, 1)),
                10, 50);
        assertThat(plan.worstSloId()).isEqualTo("b");
        assertThat(plan.worstDeficit()).isCloseTo(2.0 / 3,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void weightedDeficitUsesWeights() {
        NegotiationPlan heavy = negotiator.negotiate(List.of(
                input("a", 0.8, 0.9, 10),
                input("b", 0.5, 0.9, 1)),
                10, 50);
        NegotiationPlan light = negotiator.negotiate(List.of(
                input("a", 0.8, 0.9, 1),
                input("b", 0.5, 0.9, 10)),
                10, 50);
        assertThat(heavy.weightedDeficit())
                .isLessThan(light.weightedDeficit());
    }

    @Test
    void capAtMaxNodes() {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.1, 0.9, 1)),
                10, 12);
        assertThat(plan.suggestedNodes()).isEqualTo(12);
    }

    @Test
    void zeroWeightsFallsBackToWorst() {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.8, 0.9, 0),
                input("b", 0.5, 0.9, 0)),
                10, 50);
        assertThat(plan.weightedDeficit())
                .isEqualTo(plan.worstDeficit());
    }

    @Test
    void emptyInputsRejected() {
        assertThatThrownBy(() -> negotiator.negotiate(List.of(),
                10, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullInputsRejected() {
        assertThatThrownBy(() -> negotiator.negotiate(null,
                10, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCurrentNodesRejected() {
        assertThatThrownBy(() -> negotiator.negotiate(
                List.of(input("a", 0.5, 0.9, 1)), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMaxNodesRejected() {
        assertThatThrownBy(() -> negotiator.negotiate(
                List.of(input("a", 0.5, 0.9, 1)), 10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSloIdRejected() {
        assertThatThrownBy(() -> input("", 0.5, 0.9, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAttainmentRejected() {
        assertThatThrownBy(() -> input("a", 1.5, 0.9, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTargetRejected() {
        assertThatThrownBy(() -> input("a", 0.5, 0.0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeWeightRejected() {
        assertThatThrownBy(() -> input("a", 0.5, 0.9, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headroomBelowOneRejected() {
        assertThatThrownBy(() -> new MultiSloNegotiator(0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "attainment {0}")
    @ValueSource(doubles = {0.0, 0.5, 0.8, 0.95, 1.0})
    void parameterizedAttainments(double attainment) {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", attainment, 0.9, 1)),
                10, 50);
        if (attainment >= 0.9) {
            assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        } else {
            assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        }
    }

    @ParameterizedTest(name = "slo count {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedSloCounts(int count) {
        List<SloInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add(input("s" + i,
                    i % 2 == 0 ? 0.7 : 0.95, 0.9, 1));
        }
        NegotiationPlan plan = negotiator.negotiate(inputs,
                10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "weight {0}")
    @ValueSource(doubles = {0.0, 1.0, 5.0})
    void parameterizedWeights(double weight) {
        NegotiationPlan plan = negotiator.negotiate(List.of(
                input("a", 0.8, 0.9, weight)),
                10, 50);
        assertThat(plan.suggestedNodes()).isGreaterThanOrEqualTo(10);
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {1.0, 2.0, 4.0})
    void parameterizedHeadroomFactors(double factor) {
        MultiSloNegotiator local = new MultiSloNegotiator(factor);
        NegotiationPlan plan = local.negotiate(List.of(
                input("a", 0.5, 0.9, 1)), 10, 100);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @Test
    void concurrentNegotiationStable() throws Exception {
        List<SloInput> inputs = List.of(
                input("a", 0.8, 0.9, 1),
                input("b", 0.5, 0.9, 1));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    NegotiationPlan plan = negotiator.negotiate(
                            inputs, 10, 50);
                    assertThat(plan.worstSloId()).isEqualTo("b");
                    assertThat(plan.suggestedNodes())
                            .isBetween(10, 50);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static SloInput input(String sloId, double attainment,
                                  double target, double weight) {
        return new SloInput(sloId, attainment, target, weight);
    }
}
