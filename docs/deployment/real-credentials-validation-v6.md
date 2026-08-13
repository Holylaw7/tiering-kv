# 真实凭据验证 v6（ADR-0253，TD-076 剩余项）

## 背景

Phase 47 提供配额校验。Phase 48 增加延迟探测，形成
「可达性 + 认证 + 权限 + 配额 + 延迟」五重探测。

## 设计

```text
probeWithLatency(target, endpoint, credential, transport, auth,
                 permission, quota, latency, maxLatency)
  ├─ reachable / authenticated / allowed / withinQuota
  ├─ observedLatency = latency.latencyMillis(endpoint, timeout)
  └─ ok = 全部通过 && observedLatency >= 0 && <= maxLatency
       任一失败降级登记
```

## 降级语义

- 端点不可达 / 认证失败 / 权限拒绝 / 配额超限 / 延迟超限 → degraded +
  登记；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 延迟矩阵：25 种可达/认证/权限/配额/延迟组合（25 项展开）；
- 延迟超限/不可达登记（6 项）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 真实网络握手：Phase 49 由 Runner 执行（TD-076 剩余项）。
