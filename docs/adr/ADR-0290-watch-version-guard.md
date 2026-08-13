# ADR-0290: WATCH Version Guard

## Status

Accepted

## Context

Phase 53 的 WATCH 只返回 OK，无乐观并发校验；EXEC 可能在并发修改后
静默提交。

## Decision

采用存储版本守卫：

- `AtomicStringOps.versionOf(key)`：段读锁内返回 entry.version
  （缺失 = 0）；
- WATCH 记录 key → version 到 ConnectionContext；UNWATCH 清空；
- EXEC 前校验全部被观察键版本一致，不一致返回 nil 数组（abort）；
- 存储引擎不支持版本时回退 0（文档登记）。

## Alternatives

1. 无校验：WATCH 形同虚设；
2. 全局版本号：粒度粗；
3. 锁整个键集：并发度低。

## Consequences

优点：乐观并发语义可用、粒度细。

缺点：版本仅在 MemTable/WAL 路径维护。

风险：过期清理会 bump 版本，需与删除语义一致。

## Implementation

`AtomicStringOps.versionOf`、MemTable/WALStorageEngine、
ConnectionContext.watched、WatchCommand/UnwatchCommand/ExecCommand +
`src/test/java/io/tieringkv/command/WatchVersionGuardTest.java`。
