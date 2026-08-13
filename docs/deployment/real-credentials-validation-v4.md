# 真实凭据验证 v4（ADR-0239，TD-076 剩余项）

## 背景

Phase 45 提供认证握手。Phase 46 增加权限校验，形成
「可达性 + 认证 + 权限」三重探测。

## 设计

```text
probeWithPermission(target, endpoint, credential, transport, auth, permission)
  ├─ reachable = transport.reachable(endpoint, timeout)
  ├─ authenticated = auth.valid(endpoint, credential)
  ├─ allowed = permission.allowed(endpoint, credential)
  └─ ok = reachable && authenticated && allowed；失败降级登记
```

## 降级语义

- 端点不可达 / 认证失败 / 权限拒绝 → degraded + 登记；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 权限矩阵：可达 × 认证 × 权限（20 项展开）；
- 权限拒绝登记（6 项）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 真实网络握手：Phase 47 由 Runner 执行（TD-076 剩余项）。
