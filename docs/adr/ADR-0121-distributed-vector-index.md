# ADR-0121: Distributed Vector Index

## Status

Accepted

## Context

Phase 28 HNSW 为单机原型。规模场景需要分片、重平衡与增量构建。

## Decision

新增 `vector/cluster/`：

1. `VectorShard`：按 id hash 分片（每片独立 HNSW/暴力索引）；
2. `RebalancePlanner`：分片倾斜 → 迁移计划（复用 Migration 能力）；
3. `VectorShardManager`：分片路由 + 迁移执行 + 查询合并；
4. 增量：CDC 向量变更 → 目标分片自动更新。

## Alternatives

1. 全量索引广播：存储放大；
2. 单分片扩容：无法水平扩展。

## Consequences

优点：水平扩展、重平衡不中断、召回保持。

缺点：跨分片查询需合并 topK。

风险：迁移期间路由一致性需版本控制。

## Implementation

代码影响范围：`vector/cluster/` + 测试 +
`docs/vector/distributed-index.md`。
