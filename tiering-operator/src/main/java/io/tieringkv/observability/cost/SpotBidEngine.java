package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Spot 实时竞价（ADR-0196）：价格上限 + 中断率约束 → 中标。 */
public final class SpotBidEngine {

    /** 竞价结果。 */
    public record BidResult(String cloud, double bidPrice,
                            double marketPrice, boolean won) {
    }

    private final double maxInterruptionRate;
    private final Map<String, BidResult> lastBids =
            new ConcurrentHashMap<>();

    public SpotBidEngine(double maxInterruptionRate) {
        if (maxInterruptionRate < 0 || maxInterruptionRate > 1) {
            throw new IllegalArgumentException(
                    "max interruption rate must be in [0,1]");
        }
        this.maxInterruptionRate = maxInterruptionRate;
    }

    /** 出价：价格上限 ≥ 市场价 且 中断率 ≤ 上限 → 中标。 */
    public BidResult bid(MarketTick tick, double priceCap) {
        if (tick == null) {
            throw new IllegalArgumentException("tick required");
        }
        if (priceCap < 0) {
            throw new IllegalArgumentException(
                    "price cap must be non-negative");
        }
        boolean won = priceCap >= tick.price()
                && tick.interruptionRate() <= maxInterruptionRate;
        BidResult result = new BidResult(tick.cloud(), priceCap,
                tick.price(), won);
        lastBids.put(tick.cloud(), result);
        return result;
    }

    public Optional<BidResult> lastBid(String cloud) {
        return Optional.ofNullable(lastBids.get(cloud));
    }
}
