# ADR-0252: Regulatory Compliance Auto-Mapping

## Status

Accepted

## Context

Phase 47 的监管证书提供时间戳证明。Phase 48 需要法规条款 → 审计证据
自动映射 + 证据链生成。

## Decision

新增 `RegulatoryMappingEngine`：

- 法规条款注册（regulation → clause）；
- 证据映射：clause → 审计事件类型 → 证据提取；
- 证据链：按时间戳串联 + 签名（复用监管证书）；
- 与 RegulatoryComplianceCertificate / AutonomousComplianceAuditor /
  自治控制器联动；熔断入口保留。

## Alternatives

1. 手工映射：不可扩展；
2. 无证据链：无法审计；
3. 自动映射 + 证据链：可验证、可演进，选中。

## Consequences

优点：法规合规自动化；证据链可验证。

缺点：映射规则需持续校准。

风险：映射误判 → 证据链 + 签名兜底。

## Implementation

`cluster/scheduler/RegulatoryMappingEngine` +
`src/test/java/io/tieringkv/cluster/scheduler/RegulatoryMappingEngineTest`、
`docs/cluster/regulatory-auto-mapping.md`。
