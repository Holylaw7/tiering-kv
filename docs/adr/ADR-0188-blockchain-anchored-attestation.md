# ADR-0188: Blockchain-Anchored Attestation

## Status

Accepted

## Context

Phase 38 的签名证明可验证签发者，但无外部时间锚定；审计方无法证明
"当时确实存在"。

## Decision

1. `compliance/ChainAnchor`：证明链头哈希 → 锚定记录（链 ID + 区块号
   + 时间）；
2. `compliance/ChainVerifier`：锚定验证 + 篡改检测；
3. 与 AttestationChain / AttestationExporter 联动；
4. 验收：锚定/验证矩阵 + 锚定缺失拒绝。

## Alternatives

1. 无锚定：时间证明缺失；
2. 真实链上链：依赖外部链。

## Consequences

优点：外部时间锚定，可独立验证。

缺点：需要链模拟器/接入。

风险：锚定丢失由验证拒绝兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/blockchain-anchored-attestation.md`。
