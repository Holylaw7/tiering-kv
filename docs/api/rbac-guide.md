# RBAC 网关/RPC 接线指南

Phase 27 · ADR-0110

## 1. 网关

```text
AUTH <token> → GatewayAuthSession（连接绑定 Role）
命令层 → CommandPermissionGuard（READ/WRITE/ADMIN）
```

未认证连接仅允许 AUTH/PING；令牌过期自动注销会话。

## 2. RPC

`RpcPermissionGuard` 按调用类型要求权限域（TXN_GET=READ、
TXN_PREWRITE/COMMIT/ROLLBACK=WRITE、META_*=ADMIN、BACKUP、CDC）。

## 3. 运维

- 令牌轮换在线生效（CredentialManager）；
- 管理路径需 ADMIN 令牌，防锁死。

## 4. 限制

- RPC 帧级令牌传输字段待协议扩展（v1 兼容评审，ADR-0103）；
- 外部 IAM（OIDC）为后续方向。
