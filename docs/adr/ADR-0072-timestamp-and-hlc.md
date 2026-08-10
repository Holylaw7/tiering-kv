# ADR-0072: Timestamp and HLC

## Status

Accepted

## Context

事务需要全局单调时间戳；单机时钟回拨会破坏单调性。

## Problem

`System.currentTimeMillis()` 在回拨时产生倒退 timestamp。

## Decision

- `TimestampOracle`：原子 Long 分配，`nextTimestamp()` /
  `nextBatch(n)`，并发无重复；
- `HybridLogicalClock`：`physicalTime + logicalCounter`；
  - 本地时钟前进：physical = max(now, physical)，logical=0；
  - 时钟回拨：physical 不变，logical++（不回退）；
  - `update(remote)`：取三者最大（HLC 合并语义）；
- Oracle 起始值由 HLC 提供，保证重启不回退。

## Alternatives

1. 纯原子计数器：重启丢失进度，否决。
2. 依赖 NTP 时钟：回拨窗口不可控，否决。
3. 中心化 Oracle 服务：单点，本阶段本地 Oracle 足够。

## Consistency Model

全序时间戳：同一进程内单调；跨进程由 HLC 合并保证偏序。

## Failure Model

回拨不产生倒退；溢出（Long.MAX）显式抛错。

## Recovery Model

重启后 Oracle 从持久化/最新 HLC 恢复，不重复。

## Performance Impact

原子 LongAdder/AtomicLong，纳秒级。

## Compatibility

新增组件，不影响既有时间使用。

## Implementation

- `mvcc/TimestampOracle.java`、`mvcc/HybridLogicalClock.java`
