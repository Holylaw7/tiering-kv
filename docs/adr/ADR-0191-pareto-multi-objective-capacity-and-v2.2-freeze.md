# ADR-0191: Pareto Multi-Objective Capacity & v2.2 Freeze

## Status

Accepted

## Context

多 SLO 谈判给出单点建议，未展示 SLO × 成本 × 风险权衡前沿；
v2.1 后需要冻结 v2.2 契约。

## Decision

1. `operations/slo/ParetoCapacityOptimizer`：候选方案（节点数 × 策略）
   → 多目标评分 → Pareto 前沿；
2. 与 MultiSloNegotiator / AutoCapacityAdvisor 联动；
3. `release.yml` 扩展 v2.2.0 标签 + Phase39BenchmarkTest 接入；
4. 验收：前沿矩阵 + 支配关系 + 权重选择。

## Alternatives

1. 单点建议：无权衡视图；
2. 黑盒优化：不可解释。

## Consequences

优点：多目标权衡可解释。

缺点：候选空间需枚举。

风险：前沿偏差由支配关系测试兜底。

## Implementation

代码影响范围：`operations/slo/` + `release.yml` + 测试 +
`docs/{operations/pareto-capacity,benchmark/phase39-production-report,release/v2.2.0-release-notes}.md`。
