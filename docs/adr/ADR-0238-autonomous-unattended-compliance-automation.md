# ADR-0238: Autonomous Unattended Compliance Automation

## Status

Accepted

## Context

Phase 45 的 `AutonomousPdUnattended` 提供合规报告摘要。Phase 46 需要
全自动合规证明：策略合规校验 + 审计链签名 + 外部审计接口。

## Decision

新增 `AutonomousComplianceAuditor`：

- 合规证明：执行审计链（每轮 append-only）+ 摘要签名（HMAC）；
- 外部审计接口：导出审计链 + 校验签名；
- 与 AutonomousPdUnattended / AutonomousPdFullAutomation /
  TopologyDiscovery / 自治控制器联动；
- 熔断入口保留。

## Alternatives

1. 仅报告摘要：无法审计；
2. 中心化审计存储：单点；
3. 审计链 + 签名：可验证、可导出，选中。

## Consequences

优点：合规可验证、可导出；外部审计可接入。

缺点：签名密钥需安全托管。

风险：签名密钥泄漏 → 轮换机制兜底。

## Implementation

`cluster/scheduler/AutonomousComplianceAuditor` +
`src/test/java/io/tieringkv/cluster/scheduler/AutonomousComplianceAuditorTest`、
`docs/cluster/autonomous-unattended-compliance.md`。
