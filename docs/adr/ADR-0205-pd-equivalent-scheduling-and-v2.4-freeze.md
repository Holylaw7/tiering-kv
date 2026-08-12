# ADR-0205: PD-Equivalent Scheduling & v2.4 Freeze

## Status

Accepted

## Context

PlacementManager/BalanceScheduler 已有雏形，缺放置约束、均衡调度与
调度限流的生产化；v2.3 后需要冻结 v2.4 契约。

## Decision

1. `cluster/scheduler/PlacementScheduler`：放置约束（机架/可用区）；
2. `cluster/scheduler/RebalanceScheduler`：负载均衡计划（epoch 保护）；
3. `cluster/scheduler/QuotaScheduler`：调度配额/限流；
4. `release.yml` 扩展 v2.4.0 标签 + Phase41BenchmarkTest 接入；
5. 验收：约束矩阵 + 均衡矩阵 + 限流矩阵。

## Alternatives

1. 无放置约束：跨可用区风险；
2. 无配额：调度风暴。

## Consequences

优点：调度生产化，约束安全。

缺点：策略需配置。

风险：调度偏差由 epoch 与限流兜底。

## Implementation

代码影响范围：`cluster/scheduler/` + `release.yml` + 测试 +
`docs/{cluster/pd-equivalent-scheduling,benchmark/phase41-production-report,release/v2.4.0-release-notes}.md`。
