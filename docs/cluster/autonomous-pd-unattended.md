# 自治 PD 无人值守（ADR-0231）

## 背景

Phase 44 的 AutonomousPdFullAutomation 仍需人工审批高风险动作。
Phase 45 提供无人值守：风险自校准 + 合规证明自动化 + 熔断入口。

## 设计

```text
recordOutcome(result) → EWMA 回滚率
calibrate() → 回滚率 > 0.3 阈值 -1；< 0.05 阈值 +1；clamp [min, max]
execute(loads, maxLoad) → 使用校准阈值执行（护栏/回滚由下层保证）
complianceReport() → executions / rollbacks / rollbackRate /
                     calibratedThreshold / digest
manualCircuitBreak(reason) / resetCircuit()
```

## 验收

- 自校准矩阵：EWMA 方向 + clamp（30 项展开）；
- 执行矩阵：自动 / 审批队列（20 项展开）；
- 合规证明：报告字段 + digest 稳定性；
- 熔断 + 回滚委托（12 项）。
