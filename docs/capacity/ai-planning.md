# AI 容量规划指南（ADR-0147）

## 流程

```text
历史指标 → TrendPredictor（线性/指数 + 置信带）
       → AutoCapacityAdvisor（CapacityPlanner 节点估算）
       → Advice（风险等级 + 置信度）
```

## 风险等级

- HIGH：节点需求 ≥ 当前 ×2 或置信带相对宽度 > 1.0；
- MEDIUM：需要扩容或置信带较宽；
- LOW：容量充足且拟合稳定。

## 限制

模型为线性/指数，复杂负载（周期、突变）需人工复核；
建议必须输出置信度与风险等级，不隐藏失败项。
