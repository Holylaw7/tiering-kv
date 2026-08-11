# 企业安全白皮书

Phase 26 · ADR-0106

## 1. 模型

```text
Permission：READ / WRITE / ADMIN / BACKUP / CDC
Role：READER / WRITER / ADMIN / BACKUP_OPERATOR / CDC_CONSUMER
CredentialManager：签发（TTL）/ 校验 / 轮换 / 吊销
```

## 2. 传输安全

- RPC：TLS / HMAC-SHA256 / mTLS / 限流（Phase 12–15，ADR-0041/0046/0051）；
- 证书：CertificateManager 原子轮换（ADR-0055）。

## 3. 接入计划

- RBAC 校验在 RPC/网关层接线（Phase 27）；
- 令牌存储配合 Secret 注入，落盘加密为后续版本；
- 外部 IAM（OIDC）为演进方向，内建 RBAC 先行。

## 4. 测试

`SecurityChaosTest`（28 项）+ `SecurityEdgeTest`（10 项）：RBAC 矩阵、
令牌生命周期、并发轮换/校验、过期/吊销。
