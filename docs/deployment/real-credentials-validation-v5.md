# 真实凭据验证 v5（ADR-0246，TD-076 剩余项）

## 背景

Phase 46 提供权限校验。Phase 47 增加配额校验，形成
「可达性 + 认证 + 权限 + 配额」四重探测。

## 设计

```text
probeWithQuota(target, endpoint, credential, transport, auth, permission, quota)
  ├─ reachable = transport.reachable(endpoint, timeout)
  ├─ authenticated = auth.valid(endpoint, credential)
  ├─ allowed = permission.allowed(endpoint, credential)
  ├─ withinQuota = quota.withinQuota(endpoint, credential)
  └─ ok = 全部通过；任一失败降级登记
```

## 降级语义

- 端点不可达 / 认证失败 / 权限拒绝 / 配额超限 → degraded + 登记；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 配额矩阵：可达 × 认证 × 权限 × 配额（25 项展开）；
- 配额拒绝登记（6 项）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 真实网络握手：Phase 48 由 Runner 执行（TD-076 剩余项）。
