# ADR-0168: Multi-Cloud Cost-Aware Scheduling

## Status

Accepted

## Context

跨云执行目前按域/协调器固定路由，未按成本竞价选择执行云；多云成本
优化缺少数据主权与 SLO 约束。

## Decision

1. `observability/cost/CloudCostScheduler`：任务 → 候选云（价格 + 配额
   + 数据主权）→ 最低成本选择；
2. 约束：数据主权（DataResidencyPolicy）、SLO、配额；
3. 验收：竞价选择矩阵 + 约束拒绝矩阵。

## Alternatives

1. 固定云路由：忽略成本差；
2. 仅看价格：违反主权/SLO。

## Consequences

优点：成本优化 + 约束安全。

缺点：需要云价格与配额输入。

风险：价格波动由约束与重选兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/cost-aware-scheduling.md`。
