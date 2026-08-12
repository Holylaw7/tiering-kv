# 自治 PD 全自动（ADR-0224）

## 背景

Phase 43 的 GlobalAutonomyPdIntegration 需要人工审批。Phase 44
提供受限自治：无人工审批的自动执行，保留风险分级与人工熔断。

## 设计

```text
assessRisk(loads, maxLoad)
  ├─ moves <= lowRiskMaxMoves → LOW → 自动执行
  └─ moves > lowRiskMaxMoves  → HIGH → 审批队列

approvePending(loads, maxLoad) → 人工批准后执行（仍受护栏约束）
manualCircuitBreak(reason) / resetCircuit() → 人工熔断入口
audit() → 自动执行 / 入队 / 熔断记录
```

护栏、回滚、审计复用 GlobalAutonomyPdIntegration；只调策略，禁止
放宽一致性约束。

## 验收

- 风险矩阵：overloaded × under × 阈值（30 项展开）；
- 执行矩阵：自动 / 入队（20 项展开）；
- 熔断 + 审批 + 回滚（12 项）。
