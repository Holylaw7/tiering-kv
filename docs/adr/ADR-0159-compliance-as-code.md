# ADR-0159: Compliance-as-Code

## Status

Accepted

## Context

Phase 34 的法规映射为单版本静态表，缺少法规版本化与持续审计流水线，
法规变化无法追踪，审计依赖人工触发。

## Decision

1. `compliance/RegulationVersion`：法规版本（生效时间 + 控制项快照）；
2. `compliance/ContinuousAuditPipeline`：周期评估 → 违规报告 → 导出
   （JSON/CSV）→ 审计记录；
3. 与 RegulationMapper / AuditExporter 联动；
4. 验收：版本切换矩阵 + 流水线周期评估正确性。

## Alternatives

1. 单版本静态映射：法规变更不可追溯；
2. 外部合规平台：依赖重。

## Consequences

优点：法规可版本化、审计可自动化。

缺点：版本与流水线需维护。

风险：版本切换由生效时间矩阵兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/compliance-as-code.md`。
