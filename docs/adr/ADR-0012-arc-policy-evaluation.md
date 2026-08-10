# ADR-0012: ARC Policy Evaluation

## Status

Accepted

## Context

LFU 擅长识别长期热点，但对"突发访问模式"响应滞后；ARC（Adaptive Replacement
Cache）在近期性（recency）与频率（frequency）之间自适应，并利用 ghost 缓存
（B1/B2）感知被淘汰键的"再来访问"。需要评估是否引入 ARC。

## Decision

1. **默认淘汰策略：LFU**（ADR-0010/0011），与热度跟踪共用数据；
2. **实现 ARC 原型**（`ARCPolicy`）：T1（近期）/ T2（高频）双队列 + B1/B2
   ghost，p 参数自适应（命中 B1 增大 p、命中 B2 减小 p）；
3. **策略可插拔**：`EvictionPolicy` 接口，EvictionManager 通过配置选择
   `lfu | arc`；EVICT 事件驱动 ARC 把被淘汰键移入 ghost；
4. **最终选型**：Phase 9 以 benchmark 对比 LFU vs ARC 后定稿；TinyLFU
   （Count-Min Sketch）列为 Phase 7 候选。

## Alternatives

1. 仅 LFU：实现简单，但突发热点适应差；
2. TinyLFU：内存与精度最优，但实现/调参成本高，当前阶段风险大；
3. 仅 LRU：被任务明确禁止（"不要使用简单 LRU 替代"）。

## Consequences

**优点：** 两策略可 A/B 对比；ARC ghost 机制为冷热迁移提供"误淘汰可感知"
信号。
**缺点：** 双实现维护成本；ARC 容量以 entry 数计，与内存字节配额存在口径差。
**风险：** ARC 原型为实验性质 → 明确标注 prototype，生产默认 LFU，Phase 9
再定稿。

## Implementation

- `io.tieringkv.storage.cache.ARCPolicy`（T1/T2/B1/B2 + p）；
- `CacheConfig.arcCapacity`；
- Phase 3 交付原型；Phase 9 benchmark 对比。
