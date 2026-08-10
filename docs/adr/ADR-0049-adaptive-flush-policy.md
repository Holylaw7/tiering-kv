# ADR-0049: Adaptive Flush Policy

## Status

Accepted

## Context

Phase 6 的 Flush 使用固定水位（70/85/95%）与固定间隔。低负载下固定高
频 flush 浪费 IO，高负载下固定低频 flush 延迟落盘与内存释放。

## Problem

- 需要根据内存压力、写入速率、flush 延迟、SSTable 数量动态调整；
- 需要可观测指标（flush_queue_depth / flush_latency / write_rate）。

## Options

1. **固定水位（现状）**：简单，无法适应负载变化；
2. **自适应策略（选定）**：`AdaptiveFlushController` 综合多因子输出
   flush 间隔与水位；
3. **强化学习**：过重，原型不需要。

## Decision

采用 **AdaptiveFlushController**：

```text
输入：内存占用率 / 写入速率（滑动窗口） / flush 平均延迟 / SSTable 数
输出：flushInterval（低负载 500ms → 高负载 50ms）+ 动态高水位
```

1. 写入速率高或内存接近水位 → 缩短间隔、降低触发水位；
2. flush 延迟高或 SSTable 多 → 适度拉长间隔避免 IO 放大；
3. 指标由 FlushScheduler/StorageMetrics 上报，控制器周期评估。

## Consequences

**优点：** 负载自适应，低负载省 IO、高负载及时释放内存；
**缺点：** 策略参数需基准校准；
**风险：** 抖动 → 指标平滑（EMA）+ 变化限幅。

## Implementation

- `io.tieringkv.storage.tiering`：AdaptiveFlushController +
  FlushMetrics；
- FlushScheduler 接入动态间隔。
