# ADR-0183: Spot Interruption Migration Automation

## Status

Accepted

## Context

Phase 37 的 spot 调度只做静态竞价选择，实例中断后无自动迁移；
需要中断迁移自动化。

## Decision

1. `observability/cost/SpotMigrationPlanner`：中断事件 → 备用云选择
   （期望成本 + 约束）→ 迁移计划；
2. 与 SpotAwareScheduler 联动；
3. 约束安全 + 幂等；
4. 验收：中断迁移矩阵 + 约束拒绝 + 幂等。

## Alternatives

1. 手工迁移：RTO 高；
2. 无约束迁移：违反主权/SLO。

## Consequences

优点：中断自动迁移，RTO 低。

缺点：需要备用容量。

风险：迁移失败由重试与幂等兜底。

## Implementation

代码影响范围：`observability/cost/` + 测试 +
`docs/observability/spot-migration.md`。
