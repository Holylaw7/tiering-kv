# ADR-0189: Real-Time Spot Market Prediction

## Status

Accepted

## Context

Phase 37/38 的 spot 中断率为静态估计（TD-065），不随市场变化；
需要市场数据接入与预测。

## Decision

1. `observability/cost/SpotMarketFeed`：模拟市场数据源（价格/中断率
   时间序列）；
2. `observability/cost/SpotRatePredictor`：历史中断率 → 预测
   （移动平均/指数平滑）；
3. 与 SpotAwareScheduler / SpotMigrationPlanner 联动；
4. 验收：预测误差矩阵 + 市场接入正确性。

## Alternatives

1. 静态中断率：不反映市场；
2. 外部市场 API：依赖重。

## Consequences

优点：中断率自适应，调度更准。

缺点：预测存在误差。

风险：预测偏差由误差矩阵与保守惩罚兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/spot-market-prediction.md`。
