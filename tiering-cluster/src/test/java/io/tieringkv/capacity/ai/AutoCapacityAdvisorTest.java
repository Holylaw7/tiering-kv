package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor.Advice;
import io.tieringkv.capacity.ai.AutoCapacityAdvisor.RiskLevel;
import io.tieringkv.capacity.ai.TrendPredictor.Point;
import io.tieringkv.monitor.CapacityPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 自动容量建议（ADR-0147）：预测 → 扩容建议 + 风险等级。 */
class AutoCapacityAdvisorTest {

    private static final int SHARDS = 8;
    private static final long STORAGE_GB = 1000;
    private static final long STORAGE_PER_NODE_GB = 500;
    private static final long QPS_PER_NODE = 100;

    @Test
    void flatTrendNoScaleUp() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 100, 100, 100, 100), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.needsScaleUp()).isFalse();
        assertThat(advice.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void growingTrendScaleUpHighRisk() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200, 300, 400), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.projected()).isEqualTo(1100);
        assertThat(advice.nodes()).isEqualTo(11);
        assertThat(advice.needsScaleUp()).isTrue();
        assertThat(advice.risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void moderateGrowthMediumRisk() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 120, 140), 5,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                75);
        assertThat(advice.nodes()).isEqualTo(3);
        assertThat(advice.needsScaleUp()).isTrue();
        assertThat(advice.risk()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void decliningTrendClampedToZero() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 80, 60, 40), 20,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.projected()).isZero();
        assertThat(advice.needsScaleUp()).isFalse();
    }

    @Test
    void exponentialAdvice() {
        Advice advice = advisor().adviseExponential("qps",
                points(1, 2, 4, 8), 9,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.projected()).isEqualTo(512);
        assertThat(advice.risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void autoAdviceSelectsBetterModel() {
        Advice linear = advisor().adviseLinear("qps",
                points(100, 200, 300, 400), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        Advice auto = advisor().adviseAuto("qps",
                points(100, 200, 300, 400), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(auto.projected()).isEqualTo(linear.projected());
    }

    @Test
    void adviceCarriesMetricName() {
        Advice advice = advisor().adviseLinear("storage.gb",
                points(100, 110, 120), 5,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.metric()).isEqualTo("storage.gb");
    }

    @Test
    void confidenceWithinUnitRange() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200, 300), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void currentValueFromLatestPoint() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200, 300), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.current()).isEqualTo(300);
    }

    @Test
    void storageDrivenCapacity() {
        Advice advice = advisor().adviseLinear("qps",
                points(10, 10, 10), 5,
                SHARDS, 3000, 2, 1000, QPS_PER_NODE);
        assertThat(advice.nodes()).isEqualTo(3);
    }

    @Test
    void qpsDrivenCapacity() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200, 300), 10,
                SHARDS, 10, 1, 1000, 100);
        assertThat(advice.nodes()).isEqualTo(11);
    }

    @Test
    void riskLowWhenWithinCapacity() {
        Advice advice = advisor().adviseLinear("qps",
                points(10, 10, 10), 5,
                SHARDS, 1000, 10, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void noisyBandRaisesRisk() {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 1, 200, 2, 50, 300, 120, 80), 10,
                SHARDS, STORAGE_GB, 10, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.risk()).isNotEqualTo(RiskLevel.LOW);
    }

    @ParameterizedTest(name = "growth {0}")
    @ValueSource(ints = {100, 200, 400})
    void parameterizedGrowthRates(long rate) {
        Advice advice = advisor().adviseLinear("qps",
                points(rate, 2 * rate, 3 * rate), 10,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.projected()).isEqualTo(11 * rate);
    }

    @ParameterizedTest(name = "horizon {0}")
    @ValueSource(longs = {5, 10, 20})
    void parameterizedHorizons(long horizon) {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200), horizon,
                SHARDS, STORAGE_GB, 2, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.projected())
                .isEqualTo(100 + 100L * horizon);
    }

    @ParameterizedTest(name = "nodes {0} vs {1}")
    @CsvSource({"1,1", "2,1", "3,2", "4,2", "10,5"})
    void parameterizedRiskMatrix(int nodes, int currentNodes) {
        Advice advice = advisor().adviseLinear("qps",
                points(100, 200), 5,
                SHARDS, 10, currentNodes,
                STORAGE_PER_NODE_GB, Math.round(600.0 / nodes));
        if (nodes >= Math.max(1, currentNodes) * 2) {
            assertThat(advice.risk()).isEqualTo(RiskLevel.HIGH);
        } else if (nodes > currentNodes) {
            assertThat(advice.risk()).isEqualTo(RiskLevel.MEDIUM);
        } else {
            assertThat(advice.risk()).isEqualTo(RiskLevel.LOW);
        }
    }

    @ParameterizedTest(name = "points {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedHistorySizes(int count) {
        List<Point> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add(new Point(i, 50 + 10.0 * i));
        }
        Advice advice = advisor().adviseAuto("qps", data, count + 5,
                SHARDS, STORAGE_GB, 1, STORAGE_PER_NODE_GB,
                QPS_PER_NODE);
        assertThat(advice.confidence()).isBetween(0.0, 1.0);
    }

    private static AutoCapacityAdvisor advisor() {
        return new AutoCapacityAdvisor(new CapacityPlanner(),
                new TrendPredictor());
    }

    private static List<Point> points(long... yValues) {
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < yValues.length; i++) {
            result.add(new Point(i, yValues[i]));
        }
        return result;
    }
}
