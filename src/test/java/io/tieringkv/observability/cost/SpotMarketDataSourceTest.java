package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.SpotMarketDataSource.SourceType;
import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Spot 市场数据源（ADR-0201）：真实/模拟切换 + fallback。 */
class SpotMarketDataSourceTest {

    @Test
    void simulatedWhenNoEndpoint() {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        assertThat(source.type()).isEqualTo(SourceType.SIMULATED);
    }

    @Test
    void realWhenEndpointConfigured() {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "https://market.example.com", new SpotMarketFeed());
        assertThat(source.type()).isEqualTo(SourceType.REAL);
        assertThat(source.endpoint())
                .isEqualTo("https://market.example.com");
    }

    @Test
    void simulatedFetchPublishesTick() {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        MarketTick tick = source.fetch("aws-us", 1000);
        assertThat(tick.cloud()).isEqualTo("aws-us");
        assertThat(tick.timestampMillis()).isEqualTo(1000);
        assertThat(tick.interruptionRate()).isBetween(0.0, 1.0);
    }

    @Test
    void lastFetchTracked() {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        source.fetch("aws-us", 42);
        assertThat(source.lastFetch("aws-us"))
                .contains(42L);
    }

    @Test
    void unknownCloudLastFetchEmpty() {
        assertThat(new SpotMarketDataSource("",
                new SpotMarketFeed()).lastFetch("missing")).isEmpty();
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 100, 1000, 10_000})
    void parameterizedFetchTimes(long timestamp) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        MarketTick tick = source.fetch("aws-us", timestamp);
        assertThat(tick.timestampMillis()).isEqualTo(timestamp);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "gcp-us", "azure-us"})
    void parameterizedClouds(String cloud) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        MarketTick tick = source.fetch(cloud, 1);
        assertThat(tick.cloud()).isEqualTo(cloud);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < rounds; i++) {
            source.fetch("aws-us", i);
        }
        assertThat(source.lastFetch("aws-us"))
                .contains((long) rounds - 1);
    }

    @Test
    void concurrentFetchStable() throws Exception {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    source.fetch("aws-us", i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(source.lastFetch("aws-us")).isPresent();
    }
}
