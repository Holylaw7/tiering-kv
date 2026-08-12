package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spot 市场数据源（ADR-0201）：真实 API 抽象 + 模拟 fallback。
 */
public final class SpotMarketDataSource {

    /** 数据源类型。 */
    public enum SourceType {
        REAL,
        SIMULATED
    }

    private final String endpoint;
    private final SpotMarketFeed feed;
    private final SourceType type;
    private final Map<String, Long> lastFetch =
            new ConcurrentHashMap<>();

    public SpotMarketDataSource(String endpoint, SpotMarketFeed feed) {
        this.endpoint = endpoint;
        this.feed = feed;
        this.type = endpoint == null || endpoint.isBlank()
                ? SourceType.SIMULATED : SourceType.REAL;
    }

    /** 拉取数据：真实端点未配置时从模拟源生成。 */
    public MarketTick fetch(String cloud, long timestampMillis) {
        if (type == SourceType.SIMULATED) {
            double rate = 0.1 + (timestampMillis % 5) * 0.1;
            feed.publish(cloud, timestampMillis, 1.0, rate);
        }
        lastFetch.put(cloud, timestampMillis);
        return feed.latest(cloud);
    }

    public SourceType type() {
        return type;
    }

    public String endpoint() {
        return endpoint;
    }

    public Optional<Long> lastFetch(String cloud) {
        return Optional.ofNullable(lastFetch.get(cloud));
    }
}
