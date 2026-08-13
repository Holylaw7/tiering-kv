# ADR-0231: Autonomous PD Unattended Operation

## Status

Accepted

## Context

Phase 44 的 `AutonomousPdFullAutomation` 仍需人工审批高风险动作。
Phase 45 需要无人值守：风险自校准（根据历史回滚率动态调整阈值）+ 合规
证明自动化，同时保留熔断入口。

## Decision

新增 `AutonomousPdUnattended`：

- 风险自校准：历史回滚率 → 低风险阈值动态升降（EWMA）；
- 合规证明：自动生成执行审计 + 策略合规报告（时间戳签名）；
- 护栏复用 GlobalAutonomyPdIntegration（地域/AZ/政策/单轮上限/熔断）；
- 熔断入口：`manualCircuitBreak()` 随时冻结自治；
- 与 AutonomousPdFullAutomation / TopologyDiscovery / 自治控制器
  联动，只调策略不放松一致性。

## Alternatives

1. 保持人工审批：无法达成无人值守；
2. 完全无护栏：风险不可控；
3. 自校准 + 护栏 + 熔断：平衡安全与自动化，选中。

## Consequences

优点：常见负载变化自动收敛；合规可审计。

缺点：校准参数需持续验证。

风险：自校准误判 → 护栏兜底 + 回滚 + 审计，不产生数据迁移丢失。

## Implementation

`cluster/scheduler/AutonomousPdUnattended` +
`src/test/java/io/tieringkv/cluster/scheduler/AutonomousPdUnattendedTest`、
`docs/cluster/autonomous-pd-unattended.md`。
