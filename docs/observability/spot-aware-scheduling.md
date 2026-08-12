# Spot 感知调度指南（ADR-0175）

## 期望成本

```text
expectedCost = price × (1 + interruptionRate × penalty)
```

## 约束

- 数据主权：候选云驻留 = 任务驻留；
- 配额：availableQuota ≥ requiredQuota；
- SLO：任务要求时候选云 meetsSlo；
- 目标：最小期望成本。

## 使用

```java
SpotAwareScheduler scheduler = new SpotAwareScheduler(2.0);
scheduler.schedule(new SpotTask("t1", "us", 10, false),
        options, policy);
```

高中断率 spot 会被惩罚系数抵消价格优势。
