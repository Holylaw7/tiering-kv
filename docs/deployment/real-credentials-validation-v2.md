# 真实凭据验证 v2（ADR-0225，TD-076 关闭方向）

## 背景

Phase 43 提供 CredentialProbe 三模式探测（TD-076 JVM 方向）。Phase 44
增加真实 HTTP 探针，使 REAL 模式具备真实端点连通性检查。

## 设计

```text
CredentialProbe.realHttpProber(timeoutMillis)
  └─ HttpClient GET：2xx/3xx/4xx → 可达；异常/超时 → 不可达（降级）

Mode.REAL    ：真实探针 + 凭据校验
Mode.SIMULATED：确定性模拟
Mode.AUTO    ：端点 + 凭据存在 → REAL，否则 SIMULATED
```

## 降级语义

- 真实端点不可达 / 凭据缺失 / 端点缺失 → degraded + 登记；
- 模拟模式凭据缺失同样降级；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 探针矩阵：3 模式 × 端点/凭据/可达性（20 项展开）；
- 真实探针：非法 URI 无网络返回 false（不依赖外网）；
- 与 S3ObjectStorage / SpotMarketDataSource 联动；
- 真实网络探测：Phase 45 由 Runner 执行（TD-076 剩余项）。
