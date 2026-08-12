# ADR-0167: Compliance Continuous Proof

## Status

Accepted

## Context

Phase 35 审计流水线产生报告与 JSON 导出，但报告可被篡改且无链式证明，
无法向审计方提供可验证证据。

## Decision

1. `compliance/ComplianceAttestation`：审计运行 → 哈希链证明
   （regulation + version + violations + prevHash）；
2. `compliance/AttestationChain`：连续证明链 + 验证 API；
3. 与 ContinuousAuditPipeline 联动；
4. 验收：证明链校验矩阵 + 篡改检测。

## Alternatives

1. 仅报告：无防篡改；
2. 外部签名服务：依赖重。

## Consequences

优点：合规证据可验证、防篡改。

缺点：哈希链需持久化维护。

风险：链断裂由验证矩阵兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/continuous-proof.md`。
