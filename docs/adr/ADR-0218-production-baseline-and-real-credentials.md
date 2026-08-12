# ADR-0218: Production Baseline & Real Credentials

## Status

Accepted

## Context

缺少与 TiKV 可对比的基准口径（延迟/吞吐/内存）；S3/Spot 真实凭据未验证
（TD-076）。

## Decision

1. `benchmarks/ProductionBaseline`：A/B/C 三级基线 + 对比表；
2. `config/CredentialProbe`：S3/Spot 端点连通性 + 凭据探测（模拟/真实
   可切换）；
3. 验收：基线矩阵 + 探测矩阵 + 降级登记。

## Alternatives

1. 无基线：性能不可比；
2. 强制真实凭据：无凭据不可测。

## Consequences

优点：基线可对比、凭据可探测。

缺点：对比口径需注明。

风险：探测失败由降级兜底。

## Implementation

代码影响范围：`benchmarks/` + `config/` + 测试 +
`docs/{benchmark/production-baseline,deployment/real-credentials-validation}.md`。
