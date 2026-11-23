package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.PushdownCostModel
        .PushdownDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 下推成本模型（ADR-0229）：本地扫描 vs 传输成本决策。 */
class PushdownCostModelTest {

    @Test
    void pushdownWhenLocalCheaper() {
        PushdownCostModel model = new PushdownCostModel(0);
        PushdownDecision decision = model.shouldPushdown(
                100, 100, 10);
        assertThat(decision.pushdown()).isTrue();
        assertThat(decision.localBytes()).isEqualTo(10_000);
        assertThat(decision.transferBytes()).isEqualTo(1000);
    }

    @Test
    void noPushdownWhenTransferCheaper() {
        PushdownCostModel model = new PushdownCostModel(0);
        PushdownDecision decision = model.shouldPushdown(
                100, 10, 100);
        assertThat(decision.pushdown()).isFalse();
    }

    @Test
    void invalidCostsRejected() {
        PushdownCostModel model = new PushdownCostModel(0);
        assertThatThrownBy(() -> model.shouldPushdown(-1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.shouldPushdown(1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.shouldPushdown(1, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new PushdownCostModel(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rows={0} local={1} transfer={2}")
    @CsvSource({
            "10,100,10,true",
            "10,10,100,false",
            "100,100,10,true",
            "100,10,100,false",
            "1000,100,50,true",
            "1000,50,100,false",
            "1,1000,10,true",
            "1,10,1000,false"
    })
    void parameterizedDecisionMatrix(long rows,
                                     long localBytesPerRow,
                                     long transferBytesPerRow,
                                     boolean expected) {
        PushdownCostModel model = new PushdownCostModel(0);
        assertThat(model.shouldPushdown(rows,
                localBytesPerRow, transferBytesPerRow)
                .pushdown()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "overhead {0}")
    @ValueSource(longs = {0, 100, 1000})
    void parameterizedOverhead(long overhead) {
        PushdownCostModel model = new PushdownCostModel(overhead);
        PushdownDecision decision = model.shouldPushdown(
                100, 1000, 10);
        assertThat(decision.transferBytes())
                .isEqualTo(1000 + overhead);
    }
}
