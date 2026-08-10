# ADR-0048: MemTable Batch Write

## Status

Accepted

## Context

Phase 13 迁移基准显示 100B 小负载仅 18MB/s（≈180K entries/s）：瓶颈是
逐条 `MemTable.put` 的固定成本（分段锁、版本号、字节估算、分配）。
迁移、WAL 重放、批量复制都需要批量写入能力。

## Problem

- 需要原子批量应用（要么全部生效，要么全部拒绝）；
- 需要版本号按批内顺序分配；
- 需要 WAL 批量记录（一条记录承载多条变更）；
- 目标：100B 迁移吞吐 >100MB/s。

## Options

1. **单条 put（现状）**：每变更一次锁 + 分配；
2. **批量变更（选定）**：`BatchWriteRequest`（List<Mutation>）一次性
   apply：校验 → 按段分组 → 每段单次锁内批量插入 → 版本顺序分配；
3. **Immutable MemTable 交换（Phase 6 遗留）**：适合 Flush 轮转，
   不适合批量写入（保留为未来演进）。

## Decision

采用 **MemTable.applyBatch(BatchWriteRequest)**：

```text
BatchWriteRequest(Mutation[]: SET key value ttl / DELETE key)
  → 校验（长度/合法性）
  → 按段分组（同段变更合并为一次锁内批量操作）
  → 顺序分配全局版本号
  → 原子应用（任一条失败则整批拒绝）
```

1. `Mutation`：类型（PUT/DELETE）+ key/value/ttl；
2. `BatchWriter`：写 WAL 批量记录（单条记录含批内全部变更，CRC 保护）
   → `applyBatch` → ack；
3. 迁移与 WAL 重放使用批量接口；单条 put 保留兼容。

## Consequences

**优点：** 锁竞争与分配显著下降，小负载吞吐量级提升；
**缺点：** 批量校验增加复杂度；
**风险：** 大 batch 内存占用 → 批次大小上限（默认 4096 条）。

## Implementation

- `io.tieringkv.storage.memory`：Mutation / BatchWriteRequest /
  MemTable.applyBatch；
- `io.tieringkv.storage.wal`：WAL 批量记录编码/解码；
- `io.tieringkv.cluster.migration`：迁移复制改为批量。
