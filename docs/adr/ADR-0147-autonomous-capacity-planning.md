# ADR-0147: Autonomous Capacity Planning

## Status

Accepted

## Context

CapacityPlanner（Phase 30）仅做静态估算，缺少趋势预测与自动扩容建议；
商业化后需要"预测 → 置信带 → 建议 → 风险等级"的自治容量闭环。

## Decision

1. `capacity/ai/TrendPredictor`：线性/指数趋势 + 置信带；
2. `capacity/ai/AutoCapacityAdvisor`：预测 → 扩容建议 + 风险等级，
   与 CapacityPlanner 联动；
3. 建议必须输出置信度/风险等级，不隐藏失败项；验收预测误差矩阵。

## Alternatives

1. 仅静态估算：无法应对增长；
2. 外部 ML 服务：依赖重、可复现性差。

## Consequences

优点：轻量自治容量建议，误差可度量。

缺点：预测模型简单（线性/指数），复杂负载需人工复核。

风险：指数外推过大建议，由置信带与风险等级约束。

## Implementation

代码影响范围：`capacity/ai/` + 测试 + `docs/capacity/ai-planning.md`。
