# ADR-0160: Workload-Aware Cost Optimization

## Status

Accepted

## Context

成本归因已按租户/域/云聚合，但缺少 workload 特征（读/写/存储/迁移）
驱动的降本建议，无法给出收益与风险。

## Decision

1. `observability/cost/WorkloadCostOptimizer`：负载画像 → 降本建议
   （缩容/冷层/压缩）；
2. 与 CostAttribution / AutoCapacityAdvisor 联动；
3. 建议必须输出收益/风险等级，不隐藏失败项；
4. 验收：建议正确性矩阵 + 收益/风险估算。

## Alternatives

1. 仅成本归因：无法指导降本；
2. 人工分析：效率低。

## Consequences

优点：workload 感知降本，收益可估算。

缺点：建议需人工/护栏批准执行。

风险：误判由风险等级与审计兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/cost-optimization.md`。
