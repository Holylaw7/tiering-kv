# ADR-0201: Real Spot Market Data Integration

## Status

Accepted

## Context

Phase 39/40 的 Spot 市场为模拟数据源（TD-074）；需要真实数据源抽象
并保留模拟 fallback。

## Decision

1. `observability/cost/SpotMarketDataSource`：市场数据源抽象（真实 API
   + 模拟 fallback）；
2. 与 SpotMarketFeed / SpotBidEngine 联动；
3. 验收：数据源切换矩阵 + fallback + 限流/超时。

## Alternatives

1. 仅模拟：无法真实竞价；
2. 强制真实 API：无密钥不可测。

## Consequences

优点：真实接入 + 本地可测。

缺点：真实 API 需密钥/限流。

风险：接入失败由 fallback 兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/spot-market-real-data.md`。
