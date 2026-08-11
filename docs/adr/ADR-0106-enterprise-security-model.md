# ADR-0106: Enterprise Security Model

## Status

Accepted

## Context

Phase 12–15 已交付 RPC 安全基础（TLS / HMAC / mTLS / 证书轮换）。
v1.0 企业发布需要访问控制：RBAC、令牌生命周期（签发/轮换/吊销）与
权限域（READ / WRITE / ADMIN / BACKUP / CDC）。

## Decision

新增 `security/`：

1. `Permission`：READ / WRITE / ADMIN / BACKUP / CDC；
2. `Role`：内置角色（reader / writer / admin / backup-operator /
   cdc-consumer）到权限集合的映射；
3. `CredentialManager`：令牌签发（含过期时间）、轮换、吊销、校验
   （角色 + 权限 + 存活）；
4. TLS/mTLS 与证书轮换延续 ADR-0055，RBAC 校验接入 RPC/网关层（后续
   接入点，本阶段提供模型与测试）。

## Alternatives

1. 全局单一令牌：无法做权限隔离；
2. 外部 IAM（OIDC）：依赖外部服务，v1.0 内建轻量模型先行。

## Consequences

优点：权限模型可测试、可审计；令牌可轮换可吊销。

缺点：内建 RBAC 非完整 IAM（无用户目录/组继承），外部 IAM 为后续方向。

风险：令牌明文存储风险，需配合 Secret 与加密（后续版本）。

## Implementation

代码影响范围：

- `security/`（Permission / Role / CredentialManager）；
- `SecurityChaosTest` 与 `docs/security/security-whitepaper.md`。
