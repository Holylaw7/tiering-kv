# ADR-0239: TiKV Cross-Machine Benchmark Regression & Real Credentials v4

## Status

Accepted

## Context

Phase 45 建立跨机部署脚本与对比表（TD-076 剩余项待 Runner）。Phase 46
需要定期回归（趋势记录）与真实凭据网络握手 v4。

## Decision

- 跨机基准定期回归：多机部署脚本 + 对比表快照 + 趋势记录；
- `CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 + 权限校验 +
  失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 跨机 Runner 可执行项全绿 + 未执行项精确登记。

## Alternatives

1. 一次性跨机基准：无趋势；
2. 仅凭据可达性：缺少权限校验；
3. 强制真实凭据：无凭据环境不可运行。

## Consequences

优点：口径完整、趋势可追踪、无夸大。

缺点：跨机执行依赖 Runner 环境。

风险：对比数据被误读 → 文档显著标注口径。

## Implementation

`src/test/java/io/tieringkv/benchmark/production/Phase46ProductionBaselineTest`、
`config/CredentialProbe`（v4 扩展）+ 测试、
`docs/benchmark/tikv-cross-machine-regression.md`、
`docs/deployment/real-credentials-validation-v4.md`。
