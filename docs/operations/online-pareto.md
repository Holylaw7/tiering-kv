# 在线 Pareto 重平衡指南（ADR-0198）

## 使用

```java
OnlineParetoRebalancer rebalancer = new OnlineParetoRebalancer(5);
Rebalance result = rebalancer.rebalance(candidates, current,
        wSlo, wCost, wRisk);
```

## 语义

- 周期重算 Pareto 前沿 + 权重推荐；
- 节点变化超过 maxNodeChange → 保持当前（限幅）；
- 相同输入 → 相同推荐（幂等）；历史可审计。
