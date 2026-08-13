# TSO 跨云授时仲裁 + 防时钟回拨（ADR-0237）

## 背景

Phase 45 的 GlobalTsoClock 提供多源中位数校准，但无跨云仲裁与回拨保护。

## 设计

```text
CloudTimeSource(cloud, timestampMillis)
arbitrate() → 多数云中位数（丢弃偏离 > maxSkew 的源）
timestamp() → 单调计数器：max(arbitrate, last + 1)，绝不回拨
回拨保护：arbitrate < last - maxRollback → 冻结 + RollbackEvent 告警
restore(watermark) → 游标推进越过水位（恢复不回退）
unfreeze() → 人工/自动解除冻结
```

## 联动

- GlobalTsoClock / TsoDisasterRecovery：时间源与容灾语义复用；
- resolved-ts / 事务协调器：单调时间戳驱动水位。

## 验收

- 仲裁矩阵：3 源 × skew（35 项展开）；
- 回拨矩阵：超窗冻结 / 窗内容忍（20 项展开）；
- 单调性：并发 4000 时间戳无重复；
- 恢复不回退 + 非法入参拒绝。
