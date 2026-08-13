# TSO 全球统一时钟（ADR-0230）

## 背景

Phase 44 的 TsoDisasterRecovery 是主备模型，依赖单一时间源。Phase 45
提供混合授时源（GPS/原子钟/NTP）抽象 + 校准 + 单调。

## 设计

```text
TimeSource(type, timestampMillis)
now()   → 中位数校准（丢弃偏离 > maxSkew 的源后重算）
timestamp() → max(now, last+1)，CAS 单调绝不回拨
restore(watermark) → 游标推进越过水位（恢复不回退）
```

## 联动

- TsoDisasterRecovery：切换后以已同步水位继续分配；
- resolved-ts / 事务协调器：单调时间戳驱动水位。

## 验收

- 校准矩阵：3 源 × skew（35 项展开）；
- 单调性：并发 4000 时间戳无重复且连续；
- 恢复不回退：restore 后新时间戳 > 水位；
- 非法入参：空源 / 负 skew / 负水位拒绝。
