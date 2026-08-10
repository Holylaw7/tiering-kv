# ADR-0075: MVCC Garbage Collection

## Status

Accepted

## Context

历史版本无限增长会耗尽空间。

## Problem

需要安全删除旧版本且不破坏活跃快照。

## Decision

- `MvccGcManager` + `SafePoint`：
  - 只删除 `commitTS < safePoint` 的非最新版本；
  - 保留每个键最新版本；
  - safePoint 取 min(活跃事务 startTS, 活跃快照 readTS)；
- 支持 manual GC 与 scheduled GC（后台线程）；
- 指标：mvcc_versions_total / mvcc_gc_versions / mvcc_gc_bytes /
  mvcc_safe_point。

## Alternatives

1. 无 GC：空间无限增长，否决。
2. 删除所有旧版本：破坏长快照，否决。
3. 基于租约的版本回收：复杂，本阶段 SafePoint 足够。

## Consistency Model

不删除活跃快照可能读取的版本。

## Failure Model

GC 中断可重跑（幂等）。

## Recovery Model

GC 不修改事务状态，仅清理版本。

## Performance Impact

后台批量删除；版本越旧收益越大。

## Compatibility

底层 deleteVersion 幂等，不影响最新读。

## Implementation

- `mvcc/MvccGcManager.java`、`mvcc/SafePoint.java`
