# ADR-0035: Cluster Sharding Strategy

## Status

Accepted

## Context

集群需要把 key 映射到分片。候选：

- **Hash Slot（16384）**：CRC16(key) % 16384，slot 到分片的映射可独立于 key
  重新分配——可预测、rebalance 友好（Redis Cluster 风格）；
- **Consistent Hash**：节点增减影响小，但分片边界不直观、范围难管理；
- **Range Partition**：有序范围，天然支持范围查询，但热点与均衡需手动。

## Decision

采用 **16384 Hash Slot**：

```text
slot = CRC16(key) % 16384
slot → ShardGroup（由 Metadata 维护）→ Leader Node
```

1. 确定性：同一 key 永远同一 slot；
2. 可重映射：slot 表变更不依赖 key 分布；
3. `SlotTable` 支持批量迁移/重指派（rebalance 友好）。

## Alternatives

1. Consistent Hash：范围不可控，被否决；
2. Range Partition：均衡需人工，暂不需要范围路由。

## Consequences

**优点：** 与 Redis Cluster 对齐、迁移/再平衡简单。
**缺点：** 单 key 热点仍倾斜（热点缓解在单机层已有）。
**风险：** CRC16 冲突（不同 key 同 slot 正常，仅分布性风险）。

## Implementation

- `io.tieringkv.cluster.sharding`：HashSlotRouter / SlotTable / ShardGroup /
  ShardId / PartitionKey。
