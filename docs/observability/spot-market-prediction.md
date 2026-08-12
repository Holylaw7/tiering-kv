# Spot 市场预测指南（ADR-0189）

## 数据源

```java
SpotMarketFeed feed = new SpotMarketFeed();
feed.publish("aws-us", timestamp, price, interruptionRate);
MarketTick latest = feed.latest("aws-us");
```

## 预测

```java
double ma = predictor.movingAverage(rates, 5);
double es = predictor.exponentialSmoothing(rates, 0.5);
```

预测必须输出误差/置信，不隐藏失败项；市场接入正确性参数化验收。
