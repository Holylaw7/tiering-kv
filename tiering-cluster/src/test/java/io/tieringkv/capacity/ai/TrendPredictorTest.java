package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.TrendPredictor.Point;
import io.tieringkv.capacity.ai.TrendPredictor.Prediction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 趋势预测（ADR-0147）：线性/指数拟合 + 置信带 + 误差度量。 */
class TrendPredictorTest {

    @Test
    void linearPerfectFit() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(0, 0, 1, 2, 2, 4, 3, 6), 4);
        assertThat(prediction.value()).isEqualTo(8.0);
    }

    @Test
    void linearNegativeSlope() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(0, 10, 1, 8, 2, 6, 3, 4), 4);
        assertThat(prediction.value()).isEqualTo(2.0);
    }

    @Test
    void linearDegenerateXUsesMean() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(5, 3, 5, 5), 9);
        assertThat(prediction.value()).isEqualTo(4.0);
        assertThat(prediction.lower()).isEqualTo(4.0);
        assertThat(prediction.upper()).isEqualTo(4.0);
    }

    @Test
    void linearBandOrdering() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(0, 1, 1, 3, 2, 2, 3, 6, 4, 5), 6);
        assertThat(prediction.lower()).isLessThanOrEqualTo(
                prediction.value());
        assertThat(prediction.value()).isLessThanOrEqualTo(
                prediction.upper());
    }

    @Test
    void linearNoisyResidualBandPositive() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(0, 1, 1, 5, 2, 2, 3, 6, 4, 4), 5);
        assertThat(prediction.upper()).isGreaterThan(
                prediction.value());
    }

    @Test
    void exponentialPerfectFit() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.exponential(
                points(0, 1, 1, 2, 2, 4, 3, 8), 4);
        assertThat(prediction.value()).isCloseTo(16.0,
                org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void exponentialRequiresPositiveY() {
        TrendPredictor predictor = new TrendPredictor();
        assertThatThrownBy(() -> predictor.exponential(
                points(0, 0, 1, 2), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exponentialLowerBoundNonNegative() {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.exponential(
                points(0, 10, 1, 9, 2, 8, 3, 7), 10);
        assertThat(prediction.lower()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void autoPicksLinearOnLinearData() {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = points(0, 0, 1, 2, 2, 4, 3, 6);
        Prediction linear = predictor.linear(data, 5);
        Prediction auto = predictor.auto(data, 5);
        assertThat(auto.value()).isEqualTo(linear.value());
    }

    @Test
    void autoPicksExponentialOnExponentialData() {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = points(0, 1, 1, 2, 2, 4, 3, 8, 4, 16);
        Prediction linear = predictor.linear(data, 6);
        Prediction auto = predictor.auto(data, 6);
        assertThat(Math.abs(auto.value() - 64.0))
                .isLessThan(Math.abs(linear.value() - 64.0));
    }

    @Test
    void autoFallsBackToLinearWhenExponentialInvalid() {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = points(0, 0, 1, 2, 2, 4);
        Prediction linear = predictor.linear(data, 3);
        assertThat(predictor.auto(data, 3).value())
                .isEqualTo(linear.value());
    }

    @Test
    void fewerThanTwoPointsRejected() {
        TrendPredictor predictor = new TrendPredictor();
        assertThatThrownBy(() -> predictor.linear(
                List.of(new Point(0, 1)), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPointsRejected() {
        TrendPredictor predictor = new TrendPredictor();
        assertThatThrownBy(() -> predictor.linear(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPointsRejected() {
        TrendPredictor predictor = new TrendPredictor();
        assertThatThrownBy(() -> predictor.exponential(
                List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveBandFactorRejected() {
        assertThatThrownBy(() -> new TrendPredictor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrendPredictor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largerBandFactorWidensBand() {
        List<Point> noisy = points(0, 1, 1, 5, 2, 2, 3, 6, 4, 4);
        Prediction narrow = new TrendPredictor(1.0).linear(noisy, 5);
        Prediction wide = new TrendPredictor(3.0).linear(noisy, 5);
        assertThat(wide.upper() - wide.lower())
                .isGreaterThan(narrow.upper() - narrow.lower());
    }

    @Test
    void ssePerfectFitIsZero() {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = points(0, 1, 1, 3, 2, 5);
        assertThat(predictor.linearSse(data)).isZero();
    }

    @Test
    void sseRejectsInvalidPoints() {
        TrendPredictor predictor = new TrendPredictor();
        assertThatThrownBy(() -> predictor.linearSse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(longs = {0, 2, 5, 10})
    void parameterizedLinearTargets(long target) {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.linear(
                points(0, 0, 1, 2, 2, 4, 3, 6), target);
        assertThat(prediction.value()).isEqualTo(2.0 * target);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(longs = {0, 3, 8})
    void parameterizedExponentialTargets(long target) {
        TrendPredictor predictor = new TrendPredictor();
        Prediction prediction = predictor.exponential(
                points(0, 1, 1, 2, 2, 4), target);
        assertThat(prediction.value()).isCloseTo(
                Math.pow(2, target),
                org.assertj.core.data.Offset.offset(1e-6));
    }

    @ParameterizedTest(name = "slope {0}")
    @CsvSource({"1.0,2.0", "2.0,3.0", "-1.0,10.0"})
    void parameterizedSlopes(double slope, double start) {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            data.add(new Point(x, start + slope * x));
        }
        Prediction prediction = predictor.linear(data, 10);
        assertThat(prediction.value()).isCloseTo(
                start + slope * 10,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "points {0}")
    @ValueSource(ints = {2, 5, 20})
    void parameterizedPointCounts(int count) {
        TrendPredictor predictor = new TrendPredictor();
        List<Point> data = new ArrayList<>();
        for (int x = 0; x < count; x++) {
            data.add(new Point(x, 3 * x));
        }
        Prediction prediction = predictor.linear(data, count);
        assertThat(prediction.value()).isEqualTo(3.0 * count);
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {0.5, 1.96, 5.0})
    void parameterizedBandFactors(double factor) {
        TrendPredictor predictor = new TrendPredictor(factor);
        Prediction prediction = predictor.linear(
                points(0, 1, 1, 4, 2, 2, 3, 5), 4);
        assertThat(prediction.upper()).isGreaterThanOrEqualTo(
                prediction.value());
    }

    private static List<Point> points(long... values) {
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            result.add(new Point(values[i], values[i + 1]));
        }
        return result;
    }
}
