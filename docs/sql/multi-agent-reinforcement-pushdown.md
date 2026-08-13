# RL 多智能体下推（ADR-0250）

## 背景

Phase 47 的 ReinforcementPushdownAgent 是单智能体。Phase 48 升级为
多智能体协同决策。

## 设计

```text
MultiAgentPushdownCoordinator
  ├─ registerAgent(queryType, agent, weight)
  ├─ federatedDecide(queryType) → 加权 Q 聚合（PUSHDOWN vs KEEP_LOCAL）
  ├─ learn(queryType, action, reward) → 按权重分摊回传所有智能体
  └─ weightedQ(action) → 加权平均 Q
```

## 联动

- ReinforcementPushdownAgent：单智能体 Q 学习复用；
- SqlExecutor：协同只改决策层，语义层与上层 SQL 结果一致。

## 验收

- 联邦决策矩阵：40 种 Q/权重组合（40 项展开）；
- 反馈闭环：奖励分摊收敛（25 项展开）；
- 智能体数量 1–20（13 项展开）；权重零/未知类型/非法注册。
