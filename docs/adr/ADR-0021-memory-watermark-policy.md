# ADR-0021: Memory Watermark Policy

## Status

Accepted

## Context

自动 Flush 与背压需要明确的触发阈值。无水位时：要么过早 flush（吞吐损失），
要么过晚（内存溢出）。候选：单阈值、多级水位、比例 + 计数混合。

## Decision

采用**多级水位 + 计数/队列阈值混合**：

```text
LOW       70%  used/max        （安全区，正常写）
HIGH      85%  used/max        （触发异步 Flush）
CRITICAL  95%  used/max        （限写：awaitWritable 有界等待）
entryCount 阈值（默认 1M 键）    （与字节水位等效触发 Flush）
迁移队列阈值（默认 5K/10K 任务） （WARNING/CRITICAL 参考）
```

1. **NORMAL**：正常写；
2. **WARNING**：水位 ≥ HIGH 或 entryCount 超限 → 调度异步 Flush；
   写入不阻塞（可选节流）；
3. **CRITICAL**：水位 ≥ CRITICAL 或迁移队列超限 → 写路径
   `awaitWritable(timeout)`，超时抛 `BackpressureException`（-ERR）；
4. **恢复**：启动完成 WAL 重放后，若 used ≥ HIGH → 立即调度一次 Flush。

## Alternatives

1. 单阈值：简单但无缓冲，背压抖动大；
2. 仅字节水位：忽略键数/队列维度，内存压力可能被元数据低估；
3. 固定字节配额硬限制：正确但失去"软水位提前腾挪"能力。

## Consequences

**优点：** 提前腾挪（HIGH 预 flush）、硬保护（CRITICAL 限写）、维度完整。
**缺点：** 参数需调优（配置化默认值 70/85/95）。
**风险：** 抖动导致频繁 flush → 指标观测 + 可配置。

## Implementation

- `WatermarkManager` + `TierState{NORMAL, WARNING, CRITICAL}`；
- `BackPressureController`；`FlushScheduler` 消费水位信号。
