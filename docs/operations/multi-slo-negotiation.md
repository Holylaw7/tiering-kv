# 多 SLO 预算谈判指南（ADR-0177）

## 模型

```text
deficit_i = max(0, (target_i - attainment_i) / target_i)
weightedDeficit = Σ(weight_i × deficit_i) / Σ(weight_i)
```

## 规则

- 最差 SLO（最大 deficit）优先；
- 全部达标 → MAINTAIN；否则 SCALE_UP；
- 建议节点 = min(maxNodes, current + ceil(current × weighted ×
  headroomFactor))。

权重可配置，最差优先兜底权重偏差。
