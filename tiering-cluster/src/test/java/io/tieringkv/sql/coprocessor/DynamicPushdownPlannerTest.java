package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.DynamicPushdownPlanner
        .DynamicDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 动态下推规划器（ADR-0236）：历史统计 → 运行时决策。 */
class DynamicPushdownPlannerTest {

    @Test
    void recordUpdatesEwma() {
        DynamicPushdownPlanner planner = planner(0.5, 1);
        planner.record(100, 1000, 1000);
        assertThat(planner.shouldPushdown(100, 100, 10)
                .ewmaTransferPerRow()).isEqualTo(10.0);
        planner.record(100, 2000, 1000);
        assertThat(planner.shouldPushdown(100, 100, 10)
                .ewmaTransferPerRow()).isEqualTo(15.0);
    }

    @Test
    void dynamicPushdownWhenLocalCheaper() {
        DynamicPushdownPlanner planner = planner(0.5, 1);
        planner.record(100, 1000, 1000);
        DynamicDecision decision = planner.shouldPushdown(
                100, 100, 10);
        assertThat(decision.pushdown()).isTrue();
    }

    @Test
    void belowMinRowsNoPushdown() {
        DynamicPushdownPlanner planner = planner(0.5, 100);
        planner.record(100, 1000, 1000);
        DynamicDecision decision = planner.shouldPushdown(
                10, 100, 10);
        assertThat(decision.pushdown()).isFalse();
        assertThat(decision.reason()).contains("min rows");
    }

    @Test
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> new DynamicPushdownPlanner(
                0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPushdownPlanner(
                0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        DynamicPushdownPlanner planner = planner(0.5, 1);
        assertThatThrownBy(() -> planner.record(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.shouldPushdown(
                -1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rows={0} local={1} hist={2}")
    @CsvSource({
            "100,100,10,true",
            "100,10,100,false",
            "1000,100,10,true",
            "1000,10,100,false",
            "50,100,10,true",
            "50,10,100,false",
            "10,100,10,true",
            "10,10,100,false",
            "200,50,20,true",
            "200,20,50,false"
    })
    void parameterizedDecisionMatrix(long rows, long local,
                                     long transfer,
                                     boolean expected) {
        DynamicPushdownPlanner planner = planner(0.5, 1);
        planner.record(100, transfer * 100, 1000);
        assertThat(planner.shouldPushdown(rows, local,
                transfer).pushdown()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "alpha {0}")
    @ValueSource(doubles = {0.05, 0.1, 0.2, 0.3, 0.5,
            1.0})
    void parameterizedAlphaValues(double alpha) {
        DynamicPushdownPlanner planner = planner(alpha, 1);
        planner.record(100, 1000, 1000);
        planner.record(100, 2000, 1000);
        double first = 10.0;
        double expected = alpha * 20.0
                + (1 - alpha) * first;
        assertThat(planner.shouldPushdown(100, 100, 10)
                .ewmaTransferPerRow())
                .isCloseTo(expected,
                        org.assertj.core.data.Offset
                                .offset(1e-9));
    }

    private static DynamicPushdownPlanner planner(double alpha,
                                                  long minRows) {
        return new DynamicPushdownPlanner(alpha, minRows);
    }
}
