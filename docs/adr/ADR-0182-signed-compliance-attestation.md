# ADR-0182: Signed Compliance Attestation

## Status

Accepted

## Context

Phase 37 的证明链为哈希链，第三方只能验证完整性，无法验证签发者；
需要公钥签名。

## Decision

1. `compliance/SignedAttestation`：证明节点签名（HMAC/RSA 抽象）；
2. `compliance/SignatureVerifier`：公钥验证 + 篡改检测；
3. 与 AttestationChain / AttestationExporter 联动；
4. 验收：签名/验证矩阵 + 密钥错误拒绝。

## Alternatives

1. 仅哈希链：无签发者认证；
2. 外部 CA：依赖重。

## Consequences

优点：签发者可认证，篡改可检测。

缺点：密钥管理需维护。

风险：密钥泄露由轮换机制兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/signed-attestation.md`。
