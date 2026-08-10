# ADR-0053: Streaming Migration Design

## Status

Accepted

## Context

Phase 14 迁移 100B 负载仅 18–20MB/s，瓶颈是源端一次性快照迭代归并
（MemTable.iterator 全量拷贝 + 优先队列归并）。同时迁移期间源继续写入，
缺少版本屏障保证"不丢迁移前数据、不覆盖更新版本"。

## Problem

- 需要流式扫描（scan batch → encode → send → verify → cursor checkpoint）；
- 需要动态 batch（256–4096，按 entry size / RTT / lag 调整）；
- 需要 `MigrationStreamCursor`（slotId/lastKey/lastVersion/offset/checksum）
  支持 pause/resume/recover；
- 需要版本屏障（version barrier）：迁移开始时记录水位，只迁移
  `version <= barrier` 的条目，更新版本留给增量。

## Options

1. **一次性快照（现状）**：拷贝成本 O(N)，内存峰值高；
2. **流式迭代（选定）**：单次有序扫描分批发送，checkpoint 续传；
3. **日志订阅增量**：与快照冲突，留后续。

## Decision

采用 **StreamingMigrator**：

```text
SlotMigrationManager → StreamingMigrator
  ├── MigrationScanner（源段迭代器流式扫描，按 barrier 过滤）
  ├── BatchEncoder（Mutation 批量编码，动态 batch）
  └── MigrationSender（applyBatch 到目标 + checksum 累积）
游标：MigrationStreamCursor（slotId/lastKey/lastVersion/offset/checksum）
```

1. 迁移启动时记录 `versionBarrier = max(sourceVersion)`；
2. 只迁移 `entry.version() <= barrier`；copy 阶段完成后进入增量窗口
   （记录 barrier 后新增键，原型标记为增量待办）；
3. 动态 batch：entry 越小 batch 越大（100B → 4096，10KB → 256）；
4. 游标文件沿用 `slot-{start}.cursor`（CRC 保护）。

## Consequences

**优点：** 消除快照全量拷贝，吞吐随 batch 提升；版本屏障保证一致性；
**缺点：** 增量窗口未实现（barrier 后新写需双写/增量同步，登记限制）；
**风险：** 流式迭代期间源变更 → 版本屏障兜底。

## Implementation

- `io.tieringkv.cluster.migration.streaming`：StreamingMigrator /
  MigrationScanner / BatchEncoder / MigrationSender / MigrationStreamCursor。
