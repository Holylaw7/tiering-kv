# ADR-0174: Third-Party Compliance Attestation

## Status

Accepted

## Context

Phase 36 的证明链可自验证，但缺少独立验证 API 与导出格式，第三方
审计方无法离线校验。

## Decision

1. `compliance/AttestationVerifier`：独立验证 API（不依赖原链状态）；
2. `compliance/AttestationExporter`：证明导出（JSON）供第三方校验；
3. 与 AttestationChain 联动；
4. 验收：独立验证矩阵 + 篡改/断裂检测。

## Alternatives

1. 仅自验证：第三方不可信；
2. 外部签名服务：依赖重。

## Consequences

优点：合规证据可独立校验。

缺点：导出格式需维护。

风险：格式变化由版本化兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/third-party-attestation.md`。
