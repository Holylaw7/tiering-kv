package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spot 市场（ADR-0189）：数据源 + 预测。 */
class SpotMarketTest {

    private final SpotMarketFeed feed = new SpotMarketFeed();
    private final SpotRatePredictor predictor =
            new SpotRatePredictor();

    @Test
    void publishAndLatest() {
        feed.publish("aws-us", 1000, 1.5, 0.2);
        feed.publish("aws-us", 2000, 1.4, 0.3);
        MarketTick latest = feed.latest("aws-us");
        assertThat(latest.timestampMillis()).isEqualTo(2000);
        assertThat(latest.price()).isEqualTo(1.4);
    }

    @Test
    void historyPreservesOrder() {
        feed.publish("aws-us", 1000, 1.5, 0.2);
        feed.publish("aws-us", 2000, 1.4, 0.3);
        feed.publish("aws-us", 3000, 1.3, 0.4);
        assertThat(feed.history("aws-us")).hasSize(3);
        assertThat(feed.history("aws-us").get(2).price())
                .isEqualTo(1.3);
    }

    @Test
    void unknownCloudLatestRejected() {
        assertThatThrownBy(() -> feed.latest("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCloudHistoryEmpty() {
        assertThat(feed.history("missing")).isEmpty();
    }

    @Test
    void tickCountTracked() {
        feed.publish("gcp-us", 1, 1, 0.1);
        feed.publish("gcp-us", 2, 1, 0.1);
        assertThat(feed.tickCount("gcp-us")).isEqualTo(2);
    }

    @Test
    void invalidTickRejected() {
        assertThatThrownBy(() -> feed.publish("", 1, 1, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> feed.publish("c", 1, -1, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> feed.publish("c", 1, 1, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movingAveragePredicts() {
        double prediction = predictor.movingAverage(
                List.of(0.1, 0.2, 0.3, 0.4), 3);
        assertThat(prediction).isCloseTo(0.3,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void movingAverageWindowLargerThanData() {
        double prediction = predictor.movingAverage(
                List.of(0.2, 0.4), 10);
        assertThat(prediction).isCloseTo(0.3,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void exponentialSmoothing() {
        double prediction = predictor.exponentialSmoothing(
                List.of(0.1, 0.2, 0.3), 0.5);
        // 0.1 → 0.15 → 0.225
        assertThat(prediction).isCloseTo(0.225,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void emptyRatesRejected() {
        assertThatThrownBy(() -> predictor.movingAverage(
                List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> predictor.exponentialSmoothing(
                List.of(), 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidWindowRejected() {
        assertThatThrownBy(() -> predictor.movingAverage(
                List.of(0.1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAlphaRejected() {
        assertThatThrownBy(() -> predictor.exponentialSmoothing(
                List.of(0.1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> predictor.exponentialSmoothing(
                List.of(0.1), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "window {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedWindows(int window) {
        List<Double> rates = List.of(0.1, 0.2, 0.3, 0.4, 0.5);
        assertThat(predictor.movingAverage(rates, window))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "alpha {0}")
    @ValueSource(doubles = {0.1, 0.5, 1.0})
    void parameterizedAlphas(double alpha) {
        assertThat(predictor.exponentialSmoothing(
                List.of(0.1, 0.3, 0.5), alpha))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedTickVolumes(int count) {
        for (int i = 0; i < count; i++) {
            feed.publish("aws-us", i, 1.0, 0.1 + (i % 5) * 0.1);
        }
        assertThat(feed.tickCount("aws-us")).isEqualTo(count);
        assertThat(feed.latest("aws-us").interruptionRate())
                .isGreaterThanOrEqualTo(0.1);
    }

    @Test
    void concurrentPublishStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    feed.publish("aws-us", i, 1.0, 0.1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(feed.tickCount("aws-us")).isEqualTo(400);
    }

    @Test
    void predictorWithFeedHistory() {
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double rate = 0.1 + i * 0.02;
            feed.publish("aws-us", i, 1.0, rate);
            rates.add(rate);
        }
        double predicted = predictor.movingAverage(rates, 5);
        assertThat(predicted).isBetween(0.1, 0.3);
    }
}
