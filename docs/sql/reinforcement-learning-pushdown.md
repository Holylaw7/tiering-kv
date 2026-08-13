# RL 动态下推（ADR-0243）

## 背景

Phase 46 的 DynamicPushdownPlanner 用 EWMA 决策。Phase 47 升级为
强化学习在线决策，语义层不变。

## 设计

```text
ReinforcementPushdownAgent
  ├─ Action: PUSHDOWN / KEEP_LOCAL
  ├─ chooseAction(): epsilon-greedy（Q 表）
  ├─ learn(action, reward): Q = Q + lr * (reward - Q)，clamp ±qBound
  └─ decide(): 选择并统计（decisions / pushdowns）
```

## 联动

- DynamicPushdownPlanner：EWMA 作为初始先验；
- SqlExecutor：RL 只改决策层，语义层与上层 SQL 结果一致。

## 验收

- 单步 Q 更新：lr × reward（40 项展开）；
- 收敛：重复学习趋近 reward（25 项展开）；
- epsilon 探索 / 贪婪选择 / clamp / 确定性种子。
