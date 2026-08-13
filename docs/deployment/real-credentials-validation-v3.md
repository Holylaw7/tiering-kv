# 真实凭据验证 v3（ADR-0232，TD-076 剩余项）

## 背景

Phase 44 提供真实 HTTP 探针（TD-076 JVM 方向）。Phase 45 增加认证握手
探测，使 REAL 模式具备「连通性 + 认证」双重要求。

## 设计

```text
probeAuthenticated(target, endpoint, credential, transport, auth)
  ├─ reachable = transport.reachable(endpoint, timeout)
  ├─ authenticated = auth.valid(endpoint, credential)
  └─ ok = reachable && authenticated；失败降级登记

realAuthVerifier() → 真实实现注入点（凭据非空视为可握手）
```

## 降级语义

- 端点不可达 / 认证失败 / 端点缺失 → degraded + 登记；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 握手矩阵：可达性 × 认证（20 项展开）；
- 真实验证器：凭据非空 / 空 / null（3 项）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 真实网络握手：Phase 46 由 Runner 执行（TD-076 剩余项）。
