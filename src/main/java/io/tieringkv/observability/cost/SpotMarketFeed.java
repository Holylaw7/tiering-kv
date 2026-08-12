package io.tieringkv.observability.cost;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Spot 市场数据源（ADR-0189）：价格/中断率时间序列。 */
public final class SpotMarketFeed {

    /** 市场快照：云 + 时间 + 价格 + 中断率。 */
    public record MarketTick(String cloud, long timestampMillis,
                             double price, double interruptionRate) {

        public MarketTick {
            if (cloud == null || cloud.isBlank()) {
                throw new IllegalArgumentException(
                        "cloud required");
            }
            if (price < 0 || interruptionRate < 0
                    || interruptionRate > 1) {
                throw new IllegalArgumentException(
                        "invalid tick values");
            }
        }
    }

    private final Map<String, List<MarketTick>> ticks =
            new ConcurrentHashMap<>();

    public void publish(String cloud, long timestampMillis,
                        double price, double interruptionRate) {
        ticks.computeIfAbsent(cloud,
                ignored -> new CopyOnWriteArrayList<>())
                .add(new MarketTick(cloud, timestampMillis, price,
                        interruptionRate));
    }

    public MarketTick latest(String cloud) {
        List<MarketTick> list = ticks.get(cloud);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "no market data for " + cloud);
        }
        return list.get(list.size() - 1);
    }

    public List<MarketTick> history(String cloud) {
        List<MarketTick> list = ticks.get(cloud);
        return list == null ? List.of() : List.copyOf(list);
    }

    public int tickCount(String cloud) {
        List<MarketTick> list = ticks.get(cloud);
        return list == null ? 0 : list.size();
    }
}
