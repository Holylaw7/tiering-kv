# ADR-0071: MVCC Data Model

## Status

Accepted

## Context

需要引入多版本并发控制，同时不破坏 Phase 1–18 的 StorageEngine、
MemTable/WAL/SSTable 与 Raft 语义。

## Problem

单值存储无法提供 Snapshot Isolation 所需的历史版本与删除掩码。

## Decision

- 底层存储 key 编码为 `[userKey][commitTS(8B BE)]`（`MvccKey`）；
- `MvccEntry`：key/value/startTS/commitTS/writeType(PUT/DELETE/LOCK)；
- `MvccStorageEngine`：StorageEngine adapter，承载多版本数据，
  暴露 `putVersion / deleteVersion / readVersion / scanVersions`；
- DELETE 写入 DELETE 版本并隐藏旧版本（不物理删除）；
- 版本单调：commitTS 严格递增（由 TimestampOracle 保证）。

## Alternatives

1. 在 MemTable 内嵌版本字段：破坏 Phase 2 数据模型，否决。
2. 独立版本目录 + 指针：读路径复杂，否决。
3. 仅保留最新值：无法 Snapshot Read，否决。

## Consistency Model

Snapshot Isolation：读可见 commitTS <= readTS 的已提交版本；
未提交/回滚版本不可见。

## Failure Model

版本写入经底层 StorageEngine 原子完成；Raft 复制失败则事务状态不确认。

## Recovery Model

历史版本随底层存储持久化；恢复后 MVCC 视图完整（见 ADR-0076）。

## Performance Impact

写放大：每次 PUT 新增一个版本（GC 回收）；读为版本扫描 + 二分。

## Compatibility

StorageEngine 接口不变；`MvccStorageEngine` 以 adapter 接入。

## Implementation

- `mvcc/MvccKey.java`、`MvccVersion.java`、`MvccEntry.java`、
  `MvccStorageEngine.java`
