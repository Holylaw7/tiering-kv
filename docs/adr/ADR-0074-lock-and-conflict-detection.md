# ADR-0074: Lock and Conflict Detection

## Status

Accepted

## Context

并发事务写同一键必须互斥；读快照不得被未提交写污染。

## Problem

无锁与冲突检测导致 lost update / dirty read / dirty write。

## Decision

- `LockTable`：key → LockRecord（primary/txnId/startTS/ttl/lockType）；
  acquire/release/resolve/check；锁带 TTL 防永久锁；
- `ConflictDetector`：
  - Write-Write：存在 commitTS > startTS 的已提交版本 → 冲突；
  - Lock Conflict：目标键存在其他事务锁 → 冲突；
  - Read-Write：读集内键在 startTS 后被提交 → 冲突（SI 增强）；
- 异常：`WriteConflictException` / `LockConflictException` /
  `TransactionAbortedException`。

## Alternatives

1. 全局写锁：并发度低，否决。
2. 无冲突检测的乐观写：覆盖即丢失更新，否决。
3. 意向锁分级：复杂，本阶段不需要。

## Consistency Model

SI + 写写互斥；锁 TTL 到期可由 recovery 清理。

## Failure Model

持有锁事务崩溃 → 锁残留，由 recovery 按 TTL 清理（无永久锁）。

## Recovery Model

LockTable 可重建（扫 LockRecord 版本）+ TTL 过期清除。

## Performance Impact

ConcurrentHashMap 级锁定；预写路径 O(锁检查)。

## Compatibility

锁仅存在于 MVCC 层，不影响底层 StorageEngine。

## Implementation

- `mvcc/LockRecord.java`、`mvcc/LockTable.java`、
  `mvcc/ConflictDetector.java` + 异常类型
