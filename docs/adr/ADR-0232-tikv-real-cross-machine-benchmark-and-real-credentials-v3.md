# ADR-0232: TiKV Real Cross-Machine Benchmark & Real Credentials v3

## Status

Accepted

## Context

Phase 44 建立 A/B/C/D 本地进程内基线（TiKV 公开口径参考）。Phase 45
需要跨机对比口径与真实凭据网络验证 v3（TD-076 剩余项）。

## Decision

- 跨机基线：多机部署脚本（Gateway×3 / Metadata×3 / Storage×6）+
  对比表（公开口径 vs 本地进程内 vs 跨机待执行/已执行）；
- `CredentialProbe` 扩展真实网络探测矩阵（可达性 + 认证握手 + 失败
  登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 跨机 Runner 可执行项全绿 + 未执行项精确登记。

## Alternatives

1. 宣称跨机等效：不可信；
2. 仅本地基线：缺少跨机口径；
3. 强制真实凭据：无凭据环境不可运行。

## Consequences

优点：口径完整、可复现、无夸大。

缺点：跨机执行依赖 Runner 环境。

风险：对比数据被误读 → 文档必须显著标注口径。

## Implementation

`src/test/java/io/tieringkv/benchmark/production/Phase45ProductionBaselineTest`、
`config/CredentialProbe`（v3 扩展）+ 测试、
`docs/benchmark/tikv-cross-machine-baseline.md`、
`docs/deployment/real-credentials-validation-v3.md`。
