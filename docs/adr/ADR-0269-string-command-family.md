# ADR-0269: String Command Family

## Status

Accepted

## Context

命令层只有 SET/GET/DEL/EXISTS 等 7 个命令，缺少 INCR/APPEND/STRLEN/
GETSET/SETNX 等字符串基础命令；数值自增在并发下需要原子性保证。

## Decision

采用原子字符串操作接口 + 命令层实现：

- `AtomicStringOps`：increment / append / getSet / getAndSetPreservingTtl /
  getDelete / putIfAbsent / ttlMillis / persist / expireAt；
- MemTable 在段写锁内实现 read-modify-write（单键原子）；
- WALStorageEngine 以 WAL-first 语义委托（生产 KeyShardExecutor
  同键 FIFO 保证计算窗口一致）；
- 命令：INCR/DECR/INCRBY/DECRBY/APPEND/STRLEN/GETSET/SETNX/SETEX/
  PSETEX/GETDEL/GETRANGE/SETRANGE。

## Alternatives

1. 命令层 get+put：并发 lost update，不可接受；
2. 全局锁串行化：破坏分段并发；
3. 仅依赖 KeyShardExecutor 不做段锁：同步路径无保护。

## Consequences

优点：单键原子、TTL 保留语义正确、WAL 可重放。

缺点：多键命令跨段仍非整体原子（由批量语义 ADR 覆盖）。

风险：WAL-first 计算窗口依赖同键 FIFO，需并发测试持续覆盖。

## Implementation

`io.tieringkv.storage.AtomicStringOps`、MemTable / WALStorageEngine
实现 + `command/` 字符串命令族 +
`src/test/java/io/tieringkv/command/StringCommandFamilyTest.java`。
