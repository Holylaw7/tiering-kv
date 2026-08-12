# ADR-0172: Multi-Objective Self-Learning Fences

## Status

Accepted

## Context

Phase 36 的自学习围栏为单指标反馈（成功/失败/回滚），未考虑成本、
风险与 SLO 多目标权衡，无法精细调整策略。

## Decision

1. `capacity/ai/MultiObjectiveFence`：多指标反馈（成本节约/失败率/SLO
   达成）→ 加权评分 → 围栏参数调整；
2. 权重可配置（成本 vs 风险 vs SLO），参数变化限幅 + 上下界 + 审计；
3. 只调整策略权重/参数，禁止放宽安全核心约束；
4. 验收：权重矩阵 → 参数变化方向、约束越界拒绝。

## Alternatives

1. 单指标围栏：无法多目标权衡；
2. 无权重配置：不可调优。

## Consequences

优点：多目标自适应，权重可调。

缺点：需要多指标反馈输入。

风险：权重偏差由上下界与审计兜底。

## Implementation

代码影响范围：`capacity/ai/` + 测试 +
`docs/capacity/multi-objective-autonomy.md`。
