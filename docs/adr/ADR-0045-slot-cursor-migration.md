# ADR-0045: Slot Cursor Migration

## Status

Accepted

## Context

Phase 12 迁移每个批次重新调用 `source.iterator()`（MemTable 快照为
全量拷贝），100K 条目仅 16–20MB/s；大 slot 迁移会产生 O(N²) 扫描成本，
无法支撑十亿键级规模。

## Problem

- 需要单次扫描、跨批次续传的游标模型；
- 需要支持暂停/恢复/崩溃恢复；
- checkpoint 需 CRC 保护并明确格式（`migration/slot-{start}.cursor`）；
- 目标迁移吞吐 >100MB/s。

## Options

1. **每批重建快照迭代（现状）**：实现简单，扫描成本高；
2. **游标迁移（选定）**：任务持有单个迭代器，跨批次推进；checkpoint
   记录 `lastKey / lastVersion / checkpointOffset`；恢复时重建迭代器并
   跳过 `<= lastKey` 的已复制键；
3. **日志订阅增量迁移**：依赖完整 Raft 日志，与快照冲突，留后续。

## Decision

采用 **MigrationCursor 游标迁移**：

```text
MigrationTask → MigrationCursor（lastKey/lastVersion/checkpointOffset）
  → 单次有序扫描源（跨批次保持迭代器）
  → 每批持久化 slot-{start}.cursor（CRC 保护）
  → PAUSED（暂停）/ resume（重建迭代器跳过已复制键）
  → VERIFYING → SWITCHING → DONE
```

1. `MigrationState` 增加 `PAUSED`；暂停时关闭迭代器并落盘 checkpoint；
2. 游标文件 `migration/slot-{slotStart}.cursor`：MAGIC/VERSION/lastKey/
   lastVersion/checkpointOffset/copiedBytes/CRC32C；
3. 恢复：读取游标 → 重建迭代器 → 跳过 `key <= lastKey`；
4. 目标 >100MB/s（单线程批量 put）。

## Consequences

**优点：** 扫描成本 O(N)，checkpoint 可续传，暂停/恢复语义清晰；
**缺点：** 迁移期间源写入仍需双写/增量同步（原型记录为限制）；
**风险：** 长任务持有迭代器占用快照内存 → 按批次落盘 + PAUSED 释放。

## Future Evolution

- 增量迁移（日志订阅 + 双写）；
- 多 slot 并行迁移与限速；
- 十亿键级：分片扫描 + 外部排序校验。
