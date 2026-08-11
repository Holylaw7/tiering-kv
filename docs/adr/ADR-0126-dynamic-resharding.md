# ADR-0126: Dynamic Resharding

## Status

Accepted

## Context

Phase 29 分片为静态前缀哈希（ShardPlanner）。在线扩容/缩容需要版本化
路由、拆分/合并计划、数据迁移与原子切换，写不中断、可回滚。

## Decision

新增 `sharding/`：

1. `ShardRouter`：routing version + epoch，双写窗口与原子切换；
2. `ReshardPlanner`：拆分（1→N）/合并（N→1）计划；
3. `ShardMigration`：迁移游标 + 双写 + 校验 + 切换 + 回滚；
4. 路由版本单调，失败回滚无数据丢失。

## Alternatives

1. 停机重分片：不可接受；
2. 无版本路由：迁移期间写错分片。

## Consequences

优点：在线扩容、路由可回滚。

缺点：双写窗口有短暂写放大。

风险：切换原子性需严格测试。

## Implementation

代码影响范围：`sharding/` + 测试 +
`docs/sharding/resharding-guide.md`。
