# ADR-0039: Raft Log Storage Format

## Status

Accepted

## Context

Phase 11 的 Raft 日志完全驻留内存：节点重启后 term / votedFor / log /
commitIndex 全部丢失，无法保证已确认写入的持久性。生产分布式存储必须把
Raft 日志落盘，并支持崩溃恢复。

## Problem

- 需要一种可追加、可随机读取、可截断的日志存储；
- 需要校验尾部损坏（写一半 / 断电）；
- 需要区分同步（SYNC）、异步（ASYNC）、不落盘（NONE）三种耐久策略，
  与 WAL 的 ALWAYS / EVERY_SEC / NO 对齐；
- 需要支持未来 Snapshot 对旧日志的物理删除。

## Options

1. **复用现有 WAL 存储**：格式接近，但 WAL 面向存储命令重放，缺少 Raft
   term/index/prevLog 语义；共享路径会造成两层持久化耦合；
2. **独立 RaftLog 文件格式**（选定）：二进制记录 + CRC32C + 分段滚动；
3. **内嵌 RocksDB/LevelDB**：外部依赖重，违反"从零实现"的项目约束。

## Decision

采用独立二进制 RaftLog 格式，单条记录布局：

```text
MAGIC(4B) | VERSION(1B) | TERM(8B) | INDEX(8B) | COMMAND_TYPE(1B)
  | DATA_LENGTH(4B) | DATA(N) | CRC32C(4B)
```

1. `MAGIC = 'RLOG'`，`VERSION = 1`，单条 CRC32C 覆盖类型/长度/数据头字段；
2. 文件按段滚动：`raftlog/%06d.log`，每段固定上限（默认 64MB）；
3. 恢复流程：扫描段 → 校验 CRC → 重放合法条目 → 截断损坏尾部；
4. 耐久策略：
   - `SYNC`：每条 append 后 `FileChannel.force(false)`（对应 Raft 强持久化）；
   - `ASYNC`：缓冲写入，由调度线程周期性 force（默认，与 WAL EVERY_SEC 对齐）；
   - `NONE`：纯内存追加，仅用于测试/原型；
5. `RaftLog` 接口提供 `append / entryAt / entriesFrom / lastIndex /
   lastTerm / truncateFrom / installSnapshot`，RaftNode 只依赖接口，
   FileRaftLog 与 MemoryRaftLog 可替换。

## Consequences

**优点：**

- 重启后 term / 日志 / commitIndex 可恢复；
- CRC 校验 + 尾部截断可处理半写与断电；
- 独立格式不污染 WAL 语义，未来可加 Snapshot 索引。

**缺点：** 双写路径（WAL + RaftLog）增加一次磁盘写；
**风险：** ASYNC 默认存在 ≤1 个刷新周期的确认写入丢失窗口（与 WAL 一致），
SYNC 模式提供逐条确认的强持久化；语义差异需写入运维文档。

## Future Evolution

- Snapshot 后记录 `lastIncludedIndex` 并物理删除旧段；
- 引入 RaftLog 索引（index → 段/偏移）支持 O(logN) 随机读；
- 与 WAL group commit 合并批量 force。
