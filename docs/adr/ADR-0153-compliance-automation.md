# ADR-0153: Compliance Automation

## Status

Accepted

## Context

合规能力（数据主权/审计）分散，缺少法规映射、审计导出与违规报告，
无法支撑法规审计与持续合规。

## Decision

1. `compliance/RegulationMapper`：法规 → 控制项映射；
2. `compliance/AuditExporter`：JSON/CSV 审计导出；
3. `compliance/ComplianceReport`：违规项 + 严重级；
4. 验收：导出格式矩阵 + 映射覆盖率 + 违规报告正确性。

## Alternatives

1. 手工导出：效率低、易漏；
2. 外部合规平台：依赖重。

## Consequences

优点：持续合规可审计、可导出。

缺点：映射规则需维护。

风险：法规变化由映射版本化兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/automation.md`。
