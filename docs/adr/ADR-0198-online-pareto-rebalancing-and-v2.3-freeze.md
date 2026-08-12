# ADR-0198: Online Pareto Rebalancing & v2.3 Freeze

## Status

Accepted

## Context

Pareto 优化为静态计算，指标变化后不自动重算；v2.2 后需要冻结 v2.3
契约。

## Decision

1. `operations/slo/OnlineParetoRebalancer`：指标流 → 周期重算前沿 +
   重平衡建议；
2. 与 ParetoCapacityOptimizer 联动；
3. 重平衡限幅 + 幂等；
4. `release.yml` 扩展 v2.3.0 标签 + Phase40BenchmarkTest 接入；
5. 验收：指标流矩阵 → 前沿更新、限幅、幂等。

## Alternatives

1. 静态前沿：不随指标变化；
2. 无限制重平衡：抖动。

## Consequences

优点：容量前沿在线更新。

缺点：需要指标流输入。

风险：重平衡抖动由限幅与幂等兜底。

## Implementation

代码影响范围：`operations/slo/` + `release.yml` + 测试 +
`docs/{operations/online-pareto,benchmark/phase40-production-report,release/v2.3.0-release-notes}.md`。
