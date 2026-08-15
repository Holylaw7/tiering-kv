# ADR-0323: Leveled Compaction

## Status

Accepted

## Context

TD-012：冷层 compaction 为 size-tiered（全量合并），表数量增长导致
读放大（GET 需查多张表）。现状：`ColdStorageEngine.compactAllLocked`
把全部表合并为一张。

## Decision

- 引入 level 概念（内存元数据，不改 SSTable 文件格式）：
  `LeveledCompaction` 组件维护 `Map<level, List<SSTableMeta>>`；
- 策略：新表进入 L0；`L0 + 1` 表或 `Ln` 达到容量上限 → 与 L(n+1)
  合并；合并输出进入 L(n+1)；读取按 L0 新 → 旧、L1 → Ln 顺序；
- `ColdStorageEngine` 保留现有 size-tiered 接口作为回退/兼容，
  leveled 策略经 `LeveledCompaction` 独立可测。

## Alternatives

1. 保持 size-tiered：实现简单但读放大随表数线性增长；
2. 修改 SSTable 文件头记录 level：格式变更风险高。

## Consequences

优点：读放大受控（每 level 表数有界），兼容现有文件格式。

缺点：level 元数据内存态，重启需扫描表重建（表量可控，可接受）。

风险：合并策略正确性（latest wins / tombstone / TTL）需回归矩阵。

## Implementation

`storage/cold/LeveledCompaction.java` + 测试（合并矩阵、读顺序、
容量触发）。
