# ADR-0253: TiKV Regression Closure & Real Credentials v6

## Status

Accepted

## Context

Phase 47 建立回归告警（TD-076 剩余项待 Runner）。Phase 48 增加回归
闭环（自动重跑）与真实凭据延迟探测 v6。

## Decision

- 回归闭环：多机部署 + 快照 + 趋势 + 告警 + 自动重跑；
- `CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 + 权限 +
  配额 + 延迟探测 + 失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 跨机 Runner 可执行项全绿 + 未执行项精确登记。

## Alternatives

1. 无自动重跑：告警后人工介入；
2. 仅凭据可达性：缺少延迟维度；
3. 强制真实凭据：无凭据环境不可运行。

## Consequences

优点：口径完整、趋势可追踪、回归可闭环。

缺点：跨机执行依赖 Runner 环境。

风险：对比数据被误读 → 文档显著标注口径。

## Implementation

`src/test/java/io/tieringkv/benchmark/production/Phase48ProductionBaselineTest`、
`config/CredentialProbe`（v6 扩展）+ 测试、
`docs/benchmark/tikv-regression-closure.md`、
`docs/deployment/real-credentials-validation-v6.md`。
