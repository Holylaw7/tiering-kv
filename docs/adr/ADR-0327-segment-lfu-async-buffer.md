# ADR-0327: Segment LFU with Async Update Buffer

## Status

Accepted

## Context

TD-006：LFUPolicy 热度索引为全局同步段（单 lock + TreeSet + HashMap），
高并发访问下 onAccess 串行化成为热点。

## Decision

- 新增 `SegmentLFUPolicy implements EvictionPolicy`：按 key hash 分 N 段
  （默认 16），每段独立锁 + 频率索引（O(logN) 更新、O(1) 候选）；
- Async Buffer：onAccess 只入队（无锁路径），`drain()` 把缓冲事件
  合并到各段（调用方在 selectCandidate 前/周期触发）；
- 衰减沿用 HotnessTracker 语义（每段维护自身热度），DELETE/EVICT
  从缓冲与段索引移除；
- 现有 LFUPolicy 保留（兼容），SegmentLFUPolicy 作为默认演进候选。

## Alternatives

1. 改造现有 LFUPolicy：破坏既有测试与语义；
2. 无锁 LFU（Fraser 风格）：复杂度高，收益不确定。

## Consequences

优点：onAccess 无锁入队（热路径低延迟），分段降低索引竞争。

缺点：索引存在短暂滞后（缓冲窗口），candidate 前 drain 保证决策
基于最新事件。

风险：drain 频率与吞吐权衡——以缓冲大小/时间阈值自适应。

## Implementation

`storage/cache/SegmentLFUPolicy.java` + 测试。
