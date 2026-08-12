# 真实凭据验证（ADR-0218，TD-076 关闭方向）

## 背景

Phase 41 将 S3/Spot 收敛为客户端抽象（TD-076），但真实凭据/网络未验证。
本阶段新增 `CredentialProbe`：端点连通性 + 凭据有效性探测，失败降级登记。

## 设计

```text
CredentialProbe
  ├─ Mode.REAL      ：EndpointProber 真实探测 + 凭据非空校验
  ├─ Mode.SIMULATED ：确定性模拟（无真实凭据时的回退）
  ├─ Mode.AUTO      ：端点 + 凭据存在 → REAL；否则 SIMULATED
  ├─ probeS3(S3ObjectStorage, credential)
  ├─ probeSpot(SpotMarketDataSource, credential)
  └─ failures()     ：失败登记（target/detail/timestamp）
```

## 降级语义

- 真实端点不可达 / 凭据缺失 / 端点缺失 → `degraded=true` + 登记；
- 模拟模式凭据缺失同样降级（避免模拟态掩盖配置错误）；
- 探测结果必须如实记录，禁止伪报可用。

## 验收

- 探测矩阵：3 模式 × 端点/凭据组合（15 项）；
- 探针结果矩阵：可达性 × 凭据（10 项）；
- 与 S3ObjectStorage / SpotMarketDataSource 联动；
- 真实网络探测：Phase 44 由 Runner 执行（TD-076 剩余项）。
