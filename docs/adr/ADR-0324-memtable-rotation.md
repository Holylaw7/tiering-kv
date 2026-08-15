# ADR-0324: Active / Immutable MemTable Rotation

## Status

Accepted

## Context

TD-013：flush 为快照式（FlushScheduler 对单个 MemTable 快照），
flush 期间写入停顿/复杂化。现状：MemTable 单实例 + 后台 flush。

## Decision

- 引入 `MemTableManager`：维护 active MemTable + immutable 列表；
- 水位触发 rotation：active 转为 immutable，新建 active（WAL 段
  轮转对齐）；
- 后台 flush immutable → SSTable；读路径 active + immutable 合并
  查询；写路径始终命中 active，不等待 flush；
- 恢复：WAL 重放进 active（immutable 为内存态，崩溃后由 WAL 重建）。

## Alternatives

1. 保持快照式：实现简单但写路径停顿；
2. 无锁双 buffer：并发复杂，收益不显著。

## Consequences

优点：写路径无 flush 停顿，符合 RocksDB 模型。

缺点：读路径多表合并、WAL 段轮转联动，改造面大。

风险：恢复语义（WAL 重放 + immutable 重建）需完整回归。

## Implementation

`storage/memory/MemTableManager.java` + FlushScheduler 适配 + 测试。
