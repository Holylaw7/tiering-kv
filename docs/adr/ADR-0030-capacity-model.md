# ADR-0030: Storage Capacity Model

## Status

Accepted

## Context

需要把实测性能转化为可复用的容量估算：给定硬件与数据规模，预测 QPS、
内存、磁盘与带宽需求。

## Decision

建立四维容量模型（实测校准）：

```text
CPU：QPS/core = Level B 吞吐 / 有效核数（网络 + 协议为当前主导）
内存：MemTable(≈66B + key + value) × 热键数 + LFU 索引(≈96B/键)
      + BlockCache(容量 × 块大小) + JVM 开销(≈1.5×)
磁盘：WAL ≈ 记录字节（50B + key + value，滚动可回收）
      + SSTable ≈ 数据 + 25% 块/索引/Bloom 开销
      + Compaction 写放大 ≈ 全量 1×（触发时）
网络：请求/响应字节 × QPS（GET ≈ 100B，SET ≈ 200B 估算）
```

1. 模型参数由 Phase 9 实测校准（QPS/core、字节/op）；
2. 单节点容量 = min(CPU 上限, 内存配额上限, 磁盘吞吐上限)；
3. 扩展瓶颈：网络层（RESP 编解码 + 事件循环）优先于存储层；
4. 成本模型：内存/磁盘单价估算计入部署画像（ADR-0031）。

## Alternatives

1. 经验公式不做实测校准：不可信；
2. 仅测吞吐不建模型：无法回答容量规划。

## Consequences

**优点：** 容量规划可计算、可回归验证。
**缺点：** 模型随工作负载变化需重校准。
**风险：** 热点倾斜 → 模型给出的是均态，热点场景单独建模（Workload C/D）。

## Implementation

- `docs/benchmark/capacity-model.md`：公式 + 实测参数表 + 示例计算；
- 校准数据来自 phase9-* 报告。
