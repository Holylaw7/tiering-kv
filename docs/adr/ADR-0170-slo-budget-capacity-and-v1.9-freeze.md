# ADR-0170: SLO-Budget-Driven Capacity & v1.9 Freeze

## Status

Accepted

## Context

SLO 达成率与容量决策未联动：达成率余量/缺口没有转化为扩容建议；
v1.8 后需要冻结 v1.9 契约。

## Decision

1. `operations/slo/SloBudgetPlanner`：SLO 窗口达成率 → 容量预算
   （余量/缺口）→ 扩容建议；
2. 与 SloManager / AutoCapacityAdvisor 联动；
3. `release.yml` 扩展 v1.9.0 标签 + Phase36BenchmarkTest 接入；
4. 验收：预算矩阵 + 阈值边界 + v1.9 发布候选。

## Alternatives

1. SLO 与容量分离：过度/不足供应；
2. 不冻结：接口漂移。

## Consequences

优点：SLO 驱动容量，契约稳定。

缺点：需要窗口达成率输入。

风险：预算偏差由阈值边界测试兜底。

## Implementation

代码影响范围：`operations/slo/` + `release.yml` + 测试 +
`docs/{operations/slo-budget-capacity,benchmark/phase36-production-report,release/v1.9.0-release-notes}.md`。
