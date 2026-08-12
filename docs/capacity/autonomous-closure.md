# AI 自治闭环指南（ADR-0151）

## 容量

`AutonomousCapacityController` 护栏：

- 单步扩容上限（maxStepNodes）；
- 每日调整上限（maxDailyAdjustments，newDay 重置）；
- 高水位：目标节点超过 highWatermarkNodes 拒绝；
- 幂等：目标与当前一致 → SKIPPED；
- 失败登记：rejectedReasons 可审计。

## 流量

`AutonomousTrafficController`：

- 限幅：单步变化 ≤ 当前配额 × maxChangeFraction；
- 熔断：openCircuit 后所有调整 REJECTED，resetCircuit 恢复；
- 回滚：rollback 恢复全部原始配额（putIfAbsent 语义）。

## 原则

自治执行必须护栏内；禁止无约束自动扩容。
