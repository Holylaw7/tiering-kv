# Spot 真实数据源指南（ADR-0201）

## 使用

```java
SpotMarketDataSource source = new SpotMarketDataSource(
        endpoint, feed);
source.type(); // REAL / SIMULATED
MarketTick tick = source.fetch("aws-us", timestamp);
source.lastFetch("aws-us");
```

真实端点未配置时使用模拟数据源（fallback），接口行为一致。
