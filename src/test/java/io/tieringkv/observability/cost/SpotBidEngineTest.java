package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.SpotBidEngine.BidResult;
import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spot 实时竞价（ADR-0196）：价格/中断率约束 + 幂等。 */
class SpotBidEngineTest {

    private final SpotBidEngine engine = new SpotBidEngine(0.5);

    @Test
    void bidWinsWithinConstraints() {
        BidResult result = engine.bid(tick("aws-us", 1.0, 0.2),
                1.5);
        assertThat(result.won()).isTrue();
        assertThat(result.cloud()).isEqualTo("aws-us");
    }

    @Test
    void bidLosesAbovePriceCap() {
        BidResult result = engine.bid(tick("aws-us", 2.0, 0.2),
                1.0);
        assertThat(result.won()).isFalse();
    }

    @Test
    void bidLosesHighInterruption() {
        BidResult result = engine.bid(tick("aws-us", 1.0, 0.9),
                5.0);
        assertThat(result.won()).isFalse();
    }

    @Test
    void boundaryPriceWins() {
        assertThat(engine.bid(tick("aws-us", 1.0, 0.2), 1.0)
                .won()).isTrue();
    }

    @Test
    void boundaryInterruptionWins() {
        assertThat(engine.bid(tick("aws-us", 1.0, 0.5), 1.0)
                .won()).isTrue();
    }

    @Test
    void lastBidTracked() {
        engine.bid(tick("aws-us", 1.0, 0.2), 1.5);
        BidResult last = engine.lastBid("aws-us").orElseThrow();
        assertThat(last.won()).isTrue();
    }

    @Test
    void unknownCloudLastBidEmpty() {
        assertThat(engine.lastBid("missing")).isEmpty();
    }

    @Test
    void nullTickRejected() {
        assertThatThrownBy(() -> engine.bid(null, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativePriceCapRejected() {
        assertThatThrownBy(() -> engine.bid(
                tick("aws-us", 1.0, 0.2), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMaxRateRejected() {
        assertThatThrownBy(() -> new SpotBidEngine(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bidIdempotent() {
        MarketTick market = tick("aws-us", 1.0, 0.2);
        BidResult first = engine.bid(market, 1.5);
        BidResult second = engine.bid(market, 1.5);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.0, 1.0, 2.0, 5.0, 10.0})
    void parameterizedPrices(double price) {
        BidResult result = engine.bid(tick("aws-us", price, 0.2),
                price);
        assertThat(result.won()).isTrue();
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.8, 1.0})
    void parameterizedRates(double rate) {
        BidResult result = engine.bid(tick("aws-us", 1.0, rate),
                5.0);
        assertThat(result.won()).isEqualTo(rate <= 0.5);
    }

    @ParameterizedTest(name = "cap {0}")
    @ValueSource(doubles = {0.5, 1.0, 1.5, 2.0, 5.0})
    void parameterizedCaps(double cap) {
        BidResult result = engine.bid(tick("aws-us", 1.0, 0.2),
                cap);
        assertThat(result.won()).isEqualTo(cap >= 1.0);
    }

    @Test
    void concurrentBidsStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    engine.bid(tick("aws-us", 1.0, 0.2), 1.5);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(engine.lastBid("aws-us").orElseThrow().won())
                .isTrue();
    }

    @Test
    void bidCarriesPrices() {
        BidResult result = engine.bid(tick("aws-us", 1.2, 0.2),
                1.5);
        assertThat(result.bidPrice()).isEqualTo(1.5);
        assertThat(result.marketPrice()).isEqualTo(1.2);
    }

    private static MarketTick tick(String cloud, double price,
                                   double rate) {
        return new MarketTick(cloud, 1, price, rate);
    }
}
