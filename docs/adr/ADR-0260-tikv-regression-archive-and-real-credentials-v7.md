# ADR-0260: TiKV Regression Archive & Real Credentials v7

## Status

Accepted

## Context

Phase 48 完成 TiKV 跨机回归闭环（ADR-0253）与真实凭据 v6（延迟握手）。
Phase 49 需要回归归档执行器（快照 + 趋势 + 告警历史 + 报表）与真实
网络握手矩阵 v7（可达性/认证/权限/配额/延迟/抖动 + 失败登记 + 自动降级）。

## Decision

采用：

- `ProductionBaselineRegressionArchive`：多机部署回归快照、趋势点、
  告警历史、归档报表（GET/SET P50/P95/P99、吞吐、内存、RTT/RTO/RPO）；
- `CredentialProbe` 扩展 v7 网络握手矩阵：latency + jitter 探针、
  自动降级与失败登记；
- 对比口径如实注明（本地进程内 / 跨机 Runner），禁止伪报。

## Alternatives

1. 只保留快照：无趋势与告警，无法发现劣化；
2. 无降级机制强制探测：失败会阻塞业务；
3. 将模拟结果记为真实：违反基准真实性原则。

## Consequences

优点：回归可归档、劣化可告警、凭据探测可降级。

缺点：真实跨机项仍受 Runner 环境限制。

风险：趋势数据累积后需要归档生命周期管理。

## Implementation

`src/main/java/io/tieringkv/benchmarks/ProductionBaselineRegressionArchive.java`、
`src/main/java/io/tieringkv/config/CredentialProbe.java`（扩展 v7）+
`src/test/java/io/tieringkv/benchmark/production/Phase49ProductionBaselineTest.java`、
`src/test/java/io/tieringkv/config/CredentialProbeV7Test.java`、
`docs/benchmark/tikv-regression-archive.md`、`docs/deployment/real-credentials-validation-v7.md`。
