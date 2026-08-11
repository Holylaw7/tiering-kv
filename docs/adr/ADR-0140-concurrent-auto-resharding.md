# ADR-0140: Concurrent Auto Resharding

## Status

Accepted

## Context

Phase 31 自动重分片只触发判定，迁移为单线程占位。需要并发迁移执行：
多分片并行、限速、校验、原子切换、熔断联动。

## Decision

新增 `sharding/auto/ConcurrentReshardExecutor`：

1. 多线程逐分片迁移（复用 ShardMigration）；
2. 限速（maxMovesPerTick）+ 失败回滚 + 熔断联动；
3. 完成后校验 + ShardRouter 原子切换。

## Alternatives

1. 单线程迁移：慢；
2. 无回滚：失败放大。

## Consequences

优点：迁移吞吐提升、失败安全。

缺点：并发一致性需测试。

风险：限速参数需校准。

## Implementation

代码影响范围：`sharding/auto/` + 测试 +
`docs/sharding/concurrent-resharding.md`。
