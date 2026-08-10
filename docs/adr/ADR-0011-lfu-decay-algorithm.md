# ADR-0011: LFU Frequency Decay Algorithm

## Status

Accepted

## Context

LFU 计数器若不衰减，"长期热点"会永久占据热层，访问模式变化后仍无法淘汰。
需要定义：衰减周期、衰减方式、精度影响。

## Decision

1. **衰减周期**：`CacheConfig.decayIntervalMillis`，默认 60_000ms（可配置）；
2. **衰减方式**：每经过一个周期，频率右移 1 位（×0.5）：

   ```text
   periods = (now - lastDecayTime) / decayIntervalMillis
   frequency >>= periods（截断到 0；periods 上限 63）
   lastDecayTime += periods * decayIntervalMillis
   ```

3. **触发方式**：惰性——访问该键时按已过周期折算（O(1)），不做全局周期扫描；
   另提供 `decayAll(now)` 供后台/测试全量清扫；
4. **上限**：频率封顶 `2^40`，防止长期热点溢出；相对序不受影响。

## Alternatives

1. 全表周期扫描衰减：实现直观，但 1000 万键时扫描成本不可接受（ADR-0009 已
   确立同类原则）；
2. 指数滑动平均（EMA）：衰减连续、无整数精度问题，但需浮点状态与系数调参；
3. 固定 TTL 式降温（时间到清零）：实现简单但丢失"仍高频"信息。

## Consequences

**优点：** 惰性 O(1) 衰减，热路径无全局扫描；周期可配置；确定性可测。
**缺点：** 整数右移有精度损失（奇数频率、频率 1 直接归零）；lastDecayTime 在
高频访问下更新频繁。
**风险：** 时钟回拨 → `now <= lastDecayTime` 时跳过衰减，保证单调安全。

## Implementation

- `FrequencyCounter.incrementAndDecay / decay`；
- `HotnessTracker.record / decayAll`；
- 参数：`CacheConfig.decayIntervalMillis`。
