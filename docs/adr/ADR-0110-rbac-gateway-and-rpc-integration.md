# ADR-0110: RBAC Gateway and RPC Integration

## Status

Accepted

## Context

Phase 26 交付 RBAC 模型（Role/Permission/CredentialManager，ADR-0106），
但未接入运行路径。v1.1 需要 AUTH 命令与命令级权限校验，以及 RPC 层
权限守卫。

## Decision

1. 网关：`AUTH <token>` 命令绑定连接会话（Role）；`CommandPermissionGuard`
   按命令类型要求 READ/WRITE/ADMIN 权限；
2. RPC：`RpcPermissionGuard` 装饰端点处理器，校验调用方令牌与权限
   （令牌传输接入点随 Phase 27 交付）；
3. 令牌轮换在线生效（CredentialManager 语义不变）；
4. 未认证连接仅允许 AUTH/PING。

## Alternatives

1. 网关只做整体认证不做命令级权限：无法满足多角色；
2. RPC 全局开关：粒度不足。

## Consequences

优点：端到端 RBAC 落地；权限矩阵可测试。

缺点：RPC 令牌传输字段仍需协议扩展（v1 兼容评审）。

风险：错误配置可能锁死管理路径，需 ADMIN 兜底通道。

## Implementation

代码影响范围：`security/gateway/`（AUTH + Guard）、`security/rpc/`
（PermissionGuard）+ 测试 + `docs/api/rbac-guide.md`。
