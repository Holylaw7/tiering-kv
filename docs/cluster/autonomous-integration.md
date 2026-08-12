# 自治 PD 与全球自治联动（ADR-0217）

## 背景

自治 PD（ADR-0211）只负责计划执行；全球自治控制器（TopologyFederatedAutonomy）
输出策略权重。两者联动后，调度计划必须受护栏约束并可回滚。

## 设计

```text
GlobalAutonomyPdIntegration
  ├─ detectTopologyChange()：健康节点数变化 → 拓扑版本递增
  ├─ planAndExecute(loads)：
  │    RebalanceScheduler.plan(loads, maxLoad)
  │    → 政策护栏（TIGHTEN 权重 > 0.6 冻结本轮）
  │    → 地域护栏（源区域保留 ≥2 健康节点）
  │    → AZ 护栏（源 AZ 保留 ≥2 健康节点）
  │    → 单轮上限 / 熔断（AutonomousPdScheduler）
  │    → 执行钩子（测试注入失败）
  │    → 任一拦截 → 回滚本轮 + 审计
  └─ audit()：TOPOLOGY / GUARDRAIL / EXECUTED / ROLLBACK
```

## 原则

只调策略、禁止放宽一致性约束；回滚为进程内审计语义，真实迁移回滚由
迁移层保证。

## 验收

- 联动矩阵：节点数 1–20、maxLoad 10–1000、单轮上限 1–10；
- 护栏：地域 / AZ 最后健康节点拦截；
- 回滚：执行钩子拒绝 → ROLLBACK 审计；
- 政策冻结：TIGHTEN 权重 > 0.6 时本轮零执行。
