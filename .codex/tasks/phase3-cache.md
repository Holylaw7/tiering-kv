# Task: Phase 3 — 缓存策略

状态：⏳ 未开始

## 目标

实现 LFU / ARC 热度管理与 Bloom Filter 防击穿。

## 交付物

- cache/lfu：计数、衰减、采样窗口；
- cache/arc：自适应频率/近期性平衡；
- cache/bloom：冷读过滤；
- eviction 与迁移触发联动；
- 单元 + 热度模拟测试；
- docs/design/eviction-design.md 细化。

## 验收

- 热点数据命中率与冷热判定符合配置阈值；
- 不存在的键读取不穿透到磁盘（Bloom Filter 生效）。

## 关联

- ADR-0004。
