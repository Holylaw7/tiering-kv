# ADR-0245: Regulatory-Grade Compliance Audit

## Status

Accepted

## Context

Phase 46 的 `AutonomousComplianceAuditor` 提供 SHA-256 签名审计链。
Phase 47 需要监管级：时间戳证书 + 密钥轮换 + 外部验证。

## Decision

新增 `RegulatoryComplianceCertificate`：

- 时间戳证书：审计链摘要 + 时间戳 + 签发者 + 签名；
- 密钥轮换：证书密钥定期轮换（旧密钥保留验证）；
- 外部验证：导入证书 + 重算签名 + 时间戳校验；
- 与 AutonomousComplianceAuditor / AutonomousPdUnattended /
  自治控制器联动；熔断入口保留。

## Alternatives

1. 仅摘要签名：无时间戳证据；
2. 中心化 CA：单点；
3. 自签名证书 + 轮换：可验证、可演进，选中。

## Consequences

优点：监管可验证、可导出、可轮换。

缺点：时间戳依赖 TSO 单调性。

风险：密钥泄漏 → 轮换机制兜底。

## Implementation

`cluster/scheduler/RegulatoryComplianceCertificate` +
`src/test/java/io/tieringkv/cluster/scheduler/RegulatoryComplianceCertificateTest`、
`docs/cluster/regulatory-compliance-audit.md`。
