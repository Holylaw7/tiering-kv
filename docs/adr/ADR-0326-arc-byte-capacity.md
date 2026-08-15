# ADR-0326: ARC Byte Capacity

## Status

Accepted

## Context

TD-005：ARCPolicy 容量为 entry count，冷热分层内存预算按字节计算，
淘汰粒度与内存收益不匹配（1KB 与 10MB 值同样计 1 entry）。

## Decision

- ARCPolicy 增加字节容量模式：`ARCPolicy(long capacityBytes)`；
  T1/T2 跟踪 (timestamp, sizeBytes)，维护 usedBytes；
- onMiss 容量判断：`usedBytes + newSize > capacityBytes` 时按 ARC
  结构逐出并回收字节，直到放下；
- 保留 entry-count 构造（兼容现有调用/测试），模式由构造区分；
- AccessEvent.sizeBytes 已提供（HotnessTracker 使用），无需改事件。

## Alternatives

1. 仅 entry 口径：与内存预算脱节；
2. 替换 ARC 实现：破坏既有行为。

## Consequences

优点：淘汰收益与内存预算一致；additive。

缺点：字节模式淘汰循环复杂度（有界，每 miss 最多 capacity 级）。

风险：容量单位混淆（int vs long）——构造语义显式区分。

## Implementation

`storage/cache/ARCPolicy.java`（+byte 模式）+ 测试。
