# ADR-0195: Cross-Chain Attestation Interop

## Status

Accepted

## Context

Phase 39 的单链锚定可验证，但审计方可能信任不同链；需要多链锚定互操作。

## Decision

1. `compliance/CrossChainAnchor`：同头哈希多链锚定（chain-1..N）；
2. `compliance/CrossChainVerifier`：任一有效链验证 + 多链一致性；
3. 与 ChainAnchor / ChainVerifier 联动；
4. 验收：多链矩阵 + 一致性 + 篡改拒绝。

## Alternatives

1. 单链锚定：审计方链不兼容；
2. 无锚定：时间证明缺失。

## Consequences

优点：多链互操作，审计方自由选择。

缺点：多链维护成本。

风险：链差异由一致性验证兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/compliance/cross-chain-attestation.md`。
