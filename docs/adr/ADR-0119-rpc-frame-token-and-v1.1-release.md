# ADR-0119: RPC Frame Token and v1.1 Release

## Status

Accepted

## Context

Phase 27 RPC 权限守卫（RpcPermissionGuard）已就绪，但帧级令牌未接入
传输。v1.1 需要在不破坏 v1 旧帧的前提下携带令牌。

## Decision

1. RPC 信封版本化：envelope v1 增加可选令牌字段（长度前缀），v0 旧帧
   无令牌；
2. `MultiRaftEndpoint`：解析令牌 → CredentialManager 校验 →
   RpcPermissionGuard 按消息类型授权；
3. 未认证策略可配置（放行/拒绝），默认拒绝除 AUTH 类外全部；
4. v1.1.0 冻结与发布：release.yml 执行 rc1 → GA，旧客户端兼容矩阵
   扩展。

## Alternatives

1. 修改 RpcFrame 本体：破坏 v1 wire 格式；
2. 不接传输：权限守卫仅测试件，无运行价值。

## Consequences

优点：端到端 RPC 鉴权落地，v1 兼容。

缺点：信封长度增加；旧帧在严格模式下被拒（配置化）。

风险：令牌截获需 TLS 兜底（已有 mTLS，ADR-0046）。

## Implementation

代码影响范围：`cluster/rpc`（envelope + 校验）、`MultiRaftEndpoint` +
测试 + `docs/api/rpc-token-guide.md`。
