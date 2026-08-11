# ADR-0134: Vector Shard Double-Write Integration

## Status

Accepted

## Context

Phase 30 向量迁移为独立执行器。需要与 ShardRouter 双写窗口联动：
迁移期间写入同时落源与目标，校验后原子切换。

## Decision

扩展 `vector/cluster/`：

1. `VectorShardRouter`：版本化路由 + migrating 状态；
2. 迁移期间 `put` 双写（源 + 目标），`search` 按路由版本查询；
3. 校验通过 → 原子切换；失败回滚；
4. 验收：窗口写入不丢失、切换后召回保持。

## Alternatives

1. 迁移期间拒绝写入：不可接受；
2. 无版本路由：写错分片。

## Consequences

优点：在线迁移安全。

缺点：双写写放大。

风险：切换竞态需测试。

## Implementation

代码影响范围：`vector/cluster/` + 测试 +
`docs/vector/double-write-migration.md`。
