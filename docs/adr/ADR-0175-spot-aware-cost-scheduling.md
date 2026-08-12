# ADR-0175: Spot-Aware Cost Scheduling

## Status

Accepted

## Context

Phase 36 的多云调度按单价最低选择，未考虑 spot 实例中断率；低价但
高中断候选可能造成更高实际成本。

## Decision

1. `observability/cost/SpotAwareScheduler`：候选云 + spot 价格/中断率
   → 期望成本（价格 × 中断惩罚）→ 选择；
2. 中断感知：高中断率候选提高惩罚系数；
3. 约束：数据主权 / 配额 / SLO 不变；
4. 验收：竞价选择矩阵 + 中断率影响 + 约束拒绝。

## Alternatives

1. 仅看单价：忽略中断成本；
2. 禁用 spot：失去成本优势。

## Consequences

优点：期望成本最优，中断可感知。

缺点：需要中断率估计。

风险：中断率误差由惩罚系数兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/spot-aware-scheduling.md`。
