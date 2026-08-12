# ADR-0202: Signing Key Rotation

## Status

Accepted

## Context

Phase 38 的签名密钥静态（TD-068）；密钥泄露无法轮换，验证会中断。

## Decision

1. `compliance/KeyRotationManager`：双密钥（active/next）→ 原子切换
   + 旧密钥宽限期；
2. 与 SignedAttestation / SignatureVerifier 联动；
3. 验收：轮换矩阵 + 宽限期验证 + 回滚。

## Alternatives

1. 静态密钥：泄露不可恢复；
2. 无宽限期轮换：历史证明失效。

## Consequences

优点：密钥可轮换，历史证明可验证。

缺点：需要双密钥管理。

风险：切换失败由回滚兜底。

## Implementation

代码影响范围：`compliance/` + 测试 +
`docs/security/key-rotation.md`。
