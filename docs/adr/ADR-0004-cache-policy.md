# ADR-0004: Cache Policy

## Status

Accepted

## Context

冷热分层存储需要判断"哪些键留在内存热层"。纯 LRU 只反映近期性，无法识别长期热点；
纯 LFU 对访问模式变化不敏感且计数器无限增长；同时需要防御不存在的键反复穿透到
冷存储/磁盘。

## Decision

采用 **LFU + ARC 混合热度管理 + Bloom Filter 防击穿**：

1. **LFU 计数**：按采样窗口计数，周期性衰减，识别长期热点；
2. **ARC（Adaptive Replacement Cache）**：在近期性与频率间自适应，动态调整
   recency / frequency 预算；
3. **判定与迁移联动**：热度低于阈值的键异步降级到冷存储；读冷数据命中后升热；
4. **Bloom Filter**：置于冷读路径前方，快速过滤不存在的键，降低读放大。

实现位于 `cache/{lfu,arc,bloom}`，Phase 3 落地。

## Alternatives

1. 纯 LRU：实现简单，但冷门"一次性"键会污染热层；
2. 纯 LFU：热点稳定，但访问模式变化时适应慢，计数需衰减设计；
3. TinyLFU：效果优秀，复杂度与实现成本更高，列为后续优化候选。

## Consequences

**优点：** 热点识别准确、自适应访问模式、防击穿。
**缺点：** 双算法维护成本、采样窗口参数需调优。
**风险：** 热点 key 倾斜与窗口误判 → 通过 metrics 采样观测与参数化配置缓解。

## Implementation

- 模块：`cache/lfu`、`cache/arc`、`cache/bloom`；
- 配置：`cache.policy`、`cache.sample-window-ms`（config/tiering-kv.yaml）；
- Phase 3 实现，Phase 6 与迁移联动。
