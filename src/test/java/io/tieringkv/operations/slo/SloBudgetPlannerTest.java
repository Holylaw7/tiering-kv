package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.SloBudgetPlanner.Action;
import io.tieringkv.operations.slo.SloBudgetPlanner.BudgetPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SLO 预算容量（ADR-0170）：达成率 → 扩容建议。 */
class SloBudgetPlannerTest {

    private final SloBudgetPlanner planner = new SloBudgetPlanner();

    @Test
    void compliantMaintainsNodes() {
        BudgetPlan plan = planner.plan(0.95, 0.9, 5, 20);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        assertThat(plan.suggestedNodes()).isEqualTo(5);
    }

    @Test
    void exactTargetMaintains() {
        BudgetPlan plan = planner.plan(0.9, 0.9, 5, 20);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        assertThat(plan.suggestedNodes()).isEqualTo(5);
    }

    @Test
    void smallDeficitScalesUp() {
        BudgetPlan plan = planner.plan(0.8, 0.9, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        // deficit = 0.1/0.9 ≈ 0.111; increase = ceil(10*0.111*2)=3
        assertThat(plan.suggestedNodes()).isEqualTo(13);
    }

    @Test
    void largeDeficitLargerScaleUp() {
        BudgetPlan plan = planner.plan(0.5, 0.9, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.suggestedNodes()).isGreaterThan(13);
    }

    @Test
    void capAtMaxNodes() {
        BudgetPlan plan = planner.plan(0.1, 0.9, 10, 12);
        assertThat(plan.suggestedNodes()).isEqualTo(12);
    }

    @Test
    void zeroComplianceMaxScaleUp() {
        BudgetPlan plan = planner.plan(0.0, 0.9, 10, 100);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.suggestedNodes()).isEqualTo(30);
    }

    @Test
    void invalidComplianceRejected() {
        assertThatThrownBy(() -> planner.plan(-0.1, 0.9, 5, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(1.1, 0.9, 5, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTargetRejected() {
        assertThatThrownBy(() -> planner.plan(0.8, 0.0, 5, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(0.8, 1.1, 5, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCurrentNodesRejected() {
        assertThatThrownBy(() -> planner.plan(0.8, 0.9, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMaxNodesRejected() {
        assertThatThrownBy(() -> planner.plan(0.8, 0.9, 10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headroomFactorBelowOneRejected() {
        assertThatThrownBy(() -> new SloBudgetPlanner(0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "compliance {0} target {1}")
    @CsvSource({"0.95,0.9", "0.9,0.9", "0.8,0.9", "0.5,0.9",
            "0.0,0.9"})
    void parameterizedComplianceMatrix(double compliance,
                                       double target) {
        BudgetPlan plan = planner.plan(compliance, target, 10, 50);
        assertThat(plan.compliance()).isEqualTo(compliance);
        assertThat(plan.target()).isEqualTo(target);
        assertThat(plan.suggestedNodes()).isBetween(10, 50);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedCurrentNodes(int nodes) {
        BudgetPlan plan = planner.plan(0.5, 0.9, nodes, nodes * 4);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.suggestedNodes()).isGreaterThan(nodes);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {10, 20, 100})
    void parameterizedMaxNodes(int maxNodes) {
        BudgetPlan plan = planner.plan(0.1, 0.9, 10, maxNodes);
        assertThat(plan.suggestedNodes()).isLessThanOrEqualTo(
                maxNodes);
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {1.0, 2.0, 4.0})
    void parameterizedHeadroomFactors(double factor) {
        SloBudgetPlanner local = new SloBudgetPlanner(factor);
        BudgetPlan plan = local.plan(0.5, 0.9, 10, 100);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        // deficit=4/9≈0.444; increase=ceil(10*0.444*factor)
        assertThat(plan.suggestedNodes())
                .isEqualTo(10 + (int) Math.ceil(
                        10 * (0.4 / 0.9) * factor));
    }

    @Test
    void maintainPlanCarriesValues() {
        BudgetPlan plan = planner.plan(1.0, 0.9, 7, 20);
        assertThat(plan.currentNodes()).isEqualTo(7);
        assertThat(plan.suggestedNodes()).isEqualTo(7);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
    }

    @Test
    void scaleUpPlanCarriesValues() {
        BudgetPlan plan = planner.plan(0.7, 0.9, 8, 40);
        assertThat(plan.currentNodes()).isEqualTo(8);
        assertThat(plan.suggestedNodes()).isGreaterThan(8);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @Test
    void deficitAtLeastOneNode() {
        BudgetPlan plan = planner.plan(0.89, 0.9, 1, 10);
        assertThat(plan.suggestedNodes()).isEqualTo(2);
    }

    @Test
    void concurrentPlanningStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    BudgetPlan plan = planner.plan(0.5, 0.9,
                            10, 50);
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
}
