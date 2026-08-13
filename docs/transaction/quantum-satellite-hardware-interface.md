# 量子/卫星授时硬件适配（ADR-0251）

## 背景

Phase 47 的 QuantumSatelliteTimeSource 是模拟原型。Phase 48 提供
硬件接口，模拟实现可替换为真实设备。

## 设计

```text
HardwareClock { readTimeMillis(); healthy(); }
SimulatedHardwareClock(base, drift) → 可注入故障（fail/recover）
QuantumSatelliteHardwareAdapter
  ├─ timestamp() = 硬件时间 + 传播延迟，单调推进
  ├─ 硬件故障 → 返回上次时间戳 + 失败计数
  └─ restore(watermark) → 游标推进越过水位
```

## 联动

- QuantumSatelliteTimeSource / CrossCloudTsoArbitration：时间源可组合；
- resolved-ts / 事务协调器：单调时间戳驱动水位。

## 验收

- 校正矩阵：base × drift × delay（35 项展开）；
- 单调矩阵：20 种组合（20 项展开）；
- 故障降级：失败计数 + 恢复继续推进；并发无重复。
