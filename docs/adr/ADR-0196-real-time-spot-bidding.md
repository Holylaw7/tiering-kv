# ADR-0196: Real-Time Spot Bidding

## Status

Accepted

## Context

Phase 39 的 spot 只做预测，未参与实时竞价；需要毫秒级出价。

## Decision

1. `observability/cost/SpotBidEngine`：市场 tick → 出价（价格上限 +
   中断率约束）→ 中标/未中标；
2. 与 SpotMarketFeed / SpotRatePredictor 联动；
3. 约束安全 + 幂等；
4. 验收：出价矩阵 + 约束拒绝 + 幂等。

## Alternatives

1. 仅预测：不参与市场；
2. 无约束出价：成本风险。

## Consequences

优点：实时竞价，成本优化。

缺点：需要市场 tick 驱动。

风险：出价偏差由约束与幂等兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/spot-bidding.md`。
