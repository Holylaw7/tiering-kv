# SLO 预算驱动容量指南（ADR-0170）

## 规则

- compliance ≥ target → MAINTAIN（保持节点）；
- compliance < target → SCALE_UP；
  increase = ceil(currentNodes × deficit × headroomFactor)，
  deficit = (target - compliance) / target；
- 建议节点被 maxNodes 上限钳制。

## 使用

```java
BudgetPlan plan = new SloBudgetPlanner().plan(
        0.7, 0.9, 10, 50);
// SCALE_UP, suggestedNodes > 10
```

阈值与 headroomFactor 参数化验收。
