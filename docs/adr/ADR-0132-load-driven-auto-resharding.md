# ADR-0132: Load-Driven Auto Resharding

## Status

Accepted

## Context

Phase 30 重分片为计划/手动驱动。生产需要负载触发（QPS/延迟/分片大小）
自动拆分/合并，且异常时熔断，不放大故障。

## Decision

新增 `sharding/auto/`：

1. `LoadProbe`：QPS / 延迟 / 分片大小采样；
2. `ReshardPolicy`：阈值 + 方向（split/merge）；
3. `AutoReshardController`：采样 → 判定 → 触发迁移；连续失败/负载
   异常熔断并告警；
4. 迁移执行复用 Phase 30 ShardRouter / ShardMigration。

## Alternatives

1. 手动重分片：运维成本高；
2. 无熔断自动触发：负载异常时放大故障。

## Consequences

优点：容量自适应、异常安全。

缺点：策略参数需校准。

风险：抖动导致频繁迁移，需冷却窗口。

## Implementation

代码影响范围：`sharding/auto/` + 测试 +
`docs/sharding/auto-resharding.md`。
