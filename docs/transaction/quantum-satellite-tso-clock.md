# TSO 量子/卫星授时原型（ADR-0244）

## 背景

Phase 46 的 CrossCloudTsoArbitration 基于软件时间源。Phase 47 提供
量子/卫星授时源原型抽象。

## 设计

```text
SourceKind: QUANTUM / SATELLITE / HYBRID
corrected(sourceTime) = sourceTime + propagationDelayMillis
timestamp(sourceTime) = max(corrected, last + 1)，绝不回拨
restore(watermark) = 游标推进越过水位（恢复不回退）
```

## 联动

- CrossCloudTsoArbitration / GlobalTsoClock：时间源可组合；
- resolved-ts / 事务协调器：单调时间戳驱动水位；
- 硬件接入：原型接口 + 模拟实现。

## 验收

- 校正矩阵：源时间 × 延迟（35 项展开）；
- 单调矩阵：延迟 × 时间序列（20 项展开）；
- 并发 4000 时间戳无重复；恢复不回退。
