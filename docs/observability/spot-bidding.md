# Spot 实时竞价指南（ADR-0196）

## 规则

```text
中标 ⟺ priceCap ≥ marketPrice ∧ interruptionRate ≤ maxRate
```

## 使用

```java
SpotBidEngine engine = new SpotBidEngine(0.5);
BidResult result = engine.bid(tick, priceCap);
engine.lastBid(cloud); // 幂等可查
```

价格相等 / 中断率相等边界中标；约束安全。
