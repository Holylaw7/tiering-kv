# ADR-0246: TiKV Cross-Machine Regression Alerting & Real Credentials v5

## Status

Accepted

## Context

Phase 46 建立跨机回归快照与趋势（TD-076 剩余项待 Runner）。Phase 47
增加趋势告警与真实凭据配额校验 v5。

## Decision

- 回归告警：快照偏离基线超过阈值 → 告警事件；
- `CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 + 权限 +
  配额校验 + 失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 跨机 Runner 可执行项全绿 + 未执行项精确登记。

## Alternatives

1. 无告警：趋势漂移不可感知；
2. 仅凭据可达性：缺少配额维度；
3. 强制真实凭据：无凭据环境不可运行。

## Consequences

优点：口径完整、趋势可追踪、可告警。

缺点：跨机执行依赖 Runner 环境。

风险：对比数据被误读 → 文档显著标注口径。

## Implementation

`src/test/java/io/tieringkv/benchmark/production/Phase47ProductionBaselineTest`、
`config/CredentialProbe`（v5 扩展）+ 测试、
`docs/benchmark/tikv-cross-machine-regression-alerting.md`、
`docs/deployment/real-credentials-validation-v5.md`。
