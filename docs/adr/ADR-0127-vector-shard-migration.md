# ADR-0127: Vector Shard Migration

## Status

Accepted

## Context

Phase 29 向量重平衡仅为计划生成（RebalancePlanner）。需要真实迁移执行：
双写、校验、原子切换，查询不中断。

## Decision

扩展 `vector/cluster/`：

1. `ShardMigrationExecutor`：逐 id 迁移 + 目标校验；
2. 迁移期间双写（源 + 目标）+ 查询路由版本；
3. 校验通过后原子切换，失败回滚；
4. 验收：totalSize 一致、召回保持。

## Alternatives

1. 全量重建分片：成本高；
2. 不迁移：倾斜持续。

## Consequences

优点：重平衡真实落地、查询不中断。

缺点：迁移期间双写开销。

风险：校验不一致需回滚。

## Implementation

代码影响范围：`vector/cluster/` + 测试 +
`docs/vector/shard-migration.md`。
