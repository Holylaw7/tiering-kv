# ADR-0224: Autonomous PD Full Automation

## Status

Accepted

## Context

Phase 43 的 `GlobalAutonomyPdIntegration` 已实现「拓扑变化 → 计划 →
护栏内执行 + 回滚」，但调度仍需人工审批。Phase 44 需要受限自治：
无人工审批的自动执行，同时保留风险分级与人工熔断入口。

## Decision

新增 `AutonomousPdFullAutomation`：

- 风险分级：低风险动作自动执行；高风险动作进入审批队列或冻结；
- 护栏复用 GlobalAutonomyPdIntegration（地域/AZ/政策/单轮上限/熔断）；
- 自动回滚：执行失败自动撤销本轮并审计；
- 人工熔断入口：`manualCircuitBreak()` 可随时冻结自治；
- 与 TopologyDiscovery / 自治控制器联动，只调策略不放松一致性。

## Alternatives

1. 完全无人值守：风险不可控；
2. 保持人工审批：无法达成“全自动”目标；
3. 分级自治：平衡安全与自动化，选中。

## Consequences

优点：常见负载变化自动收敛；高风险动作仍受控。

缺点：风险分级规则需要持续校准。

风险：分级误判 → 护栏兜底 + 回滚 + 审计，不产生数据迁移丢失。

## Implementation

`cluster/scheduler/AutonomousPdFullAutomation` +
`src/test/java/io/tieringkv/cluster/scheduler/AutonomousPdFullAutomationTest`、
`docs/cluster/autonomous-pd-full-automation.md`。
