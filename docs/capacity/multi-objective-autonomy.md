# 多目标自治指南（ADR-0172）

## 评分模型

```text
score = (w_cost × costSaving
       + w_risk × (1 - failureRate)
       + w_slo × sloAttainment) / (w_cost + w_risk + w_slo)
```

## 规则

- score ≥ relaxThreshold → 放宽（relaxStep）；
- score ≤ tightenThreshold → 收紧（tightenStep）；
- 中间 → 保持；回滚 → 熔断；
- 参数被 Bounds 上下界钳制，全程审计。

只调整策略权重/参数，禁止放宽安全核心约束。
