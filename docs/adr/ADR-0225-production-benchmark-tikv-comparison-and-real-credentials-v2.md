# ADR-0225: Production Benchmark TiKV Comparison & Real Credentials v2

## Status

Accepted

## Context

Phase 43 建立了 A/B/C 三级本地进程内基线，但缺少分布式全链路（D 级）
和 TiKV 对比口径；S3/Spot 凭据探测（TD-076）只覆盖模拟与 JVM 语义。
Phase 44 需要扩展 D 级基线 + TiKV 公开口径对比表 + 真实 HTTP 探针。

## Decision

- `ProductionBaseline` 扩展 D 级：多 Region 全链路（Raft 复制 +
  WAL + SSTable + mmap），进程内口径；
- TiKV 对比表：公开数据（官方文档/社区） vs 本地进程内 vs 跨机待执行，
  所有对比注明口径；
- `CredentialProbe` 扩展真实 HTTP 探针（默认模拟，可切换 REAL），
  失败降级登记不变；
- 与 S3ObjectStorage / SpotMarketDataSource 联动。

## Alternatives

1. 直接宣称与 TiKV 等价：不可信；
2. 仅本地基线：缺少分布式口径；
3. 强制真实凭据：无凭据环境不可运行。

## Consequences

优点：口径完整、可复现、无夸大。

缺点：D 级为进程内多副本模拟，不等同跨机网络。

风险：对比数据被误读 → 文档必须显著标注口径。

## Implementation

`src/test/java/io/tieringkv/benchmark/production/Phase44ProductionBaselineTest`、
`config/CredentialProbe`（真实探针扩展）+ 测试、
`docs/benchmark/tikv-comparison-baseline.md`、
`docs/deployment/real-credentials-validation-v2.md`。
