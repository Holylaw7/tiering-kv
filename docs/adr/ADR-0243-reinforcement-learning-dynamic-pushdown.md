# ADR-0243: Reinforcement Learning Dynamic Pushdown

## Status

Accepted

## Context

Phase 46 的 `DynamicPushdownPlanner` 用 EWMA 做运行时决策。Phase 47
升级为强化学习在线决策：状态 → 动作 → 奖励 → Q 更新，语义层不变。

## Decision

新增 `ReinforcementPushdownAgent`：

- 状态：历史统计特征（行数 / 本地成本 / 传输成本 / 近期决策）；
- 动作：下推 / 不下推；
- 奖励：耗时节省（传输字节 vs 本地扫描）+ 正确性奖励；
- Q 学习更新（epsilon-greedy）；
- 与 DynamicPushdownPlanner / SqlExecutor 联动；
- 语义层与上层 SQL 结果一致（RL 只改决策层）。

## Alternatives

1. 仅 EWMA：无法自适应复杂模式；
2. 全量下推：忽略传输收益；
3. 无反馈闭环：决策不可演进。

## Consequences

优点：在线自适应；决策可解释（Q 表审计）。

缺点：探索期可能次优（epsilon 控制）。

风险：Q 表漂移 → 语义层不变 + 护栏兜底。

## Implementation

`sql/coprocessor/ReinforcementPushdownAgent` +
`src/test/java/io/tieringkv/sql/coprocessor/ReinforcementPushdownAgentTest`、
`docs/sql/reinforcement-learning-pushdown.md`。
