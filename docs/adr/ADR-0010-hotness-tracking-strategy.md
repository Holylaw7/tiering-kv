# ADR-0010: Hotness Tracking Strategy

## Status

Accepted

## Context

冷热分层需要判断"哪些键值得留在内存热层"。候选方案：

- 纯 Counter：只统计访问次数，无时间维度，热点永久占用；
- LFU（计数 + 周期衰减）：兼顾频率与时间，可识别"曾经热但已冷"的键；
- Sliding Window：按时间窗计数，精度高但每键需要窗口数组，内存开销大；
- TinyLFU：Count-Min Sketch 近似计数 + 准入缓存，内存效率高但实现复杂。

要求：每次 GET / SET / UPDATE / DELETE 产生访问事件；热度数据支撑淘汰决策与
未来冷热迁移。

## Decision

采用 **LFU 计数 + 周期衰减**（`HotnessTracker` + `FrequencyCounter`）：

1. 数据模型 `HotnessEntry`：key / frequency / lastAccessTime / createTime /
   sizeBytes / lastDecayTime；
2. 访问事件 `AccessEvent{key, operation, timestamp, sizeBytes}`，每次读/写/删
   由 TrackingStorageEngine 装饰器产生（MemTable 核心不变）；
3. 衰减采用懒计算：访问时按已过衰减周期折算（ADR-0011），并保留 `decayAll`
   全量清扫入口；
4. 频率仅作为相对热度排序依据，不追求绝对值精度。

## Alternatives

1. 纯 Counter：无时间维度，热点键永不降温 → 否决；
2. Sliding Window：精度更高，但每键多窗口数组的内存成本与实现复杂度不匹配
   当前阶段；
3. TinyLFU：内存效率最优，但 Count-Min Sketch 的误差与准入机制增加调参难度，
   列为 Phase 7 优化候选（需新 ADR）。

## Consequences

**优点：** 实现简单、内存开销低（每键 ~4 个 long/int）、可配置衰减、可测试
（注入时钟）。
**缺点：** 整数衰减存在精度损失（频率 1 衰减后归零）；近似热度而非精确统计。
**风险：** 频率突变（突发热点）响应滞后 → 衰减周期可配置 + ARC 原型兜底
（ADR-0012）。

## Implementation

- `io.tieringkv.storage.cache`：AccessEvent、HotnessEntry、FrequencyCounter、
  HotnessTracker；
- 接入：`TrackingStorageEngine`（StorageEngine 装饰器）+ `EvictionManager`；
- Phase 3 交付；Phase 6 迁移决策复用同一热度数据。
