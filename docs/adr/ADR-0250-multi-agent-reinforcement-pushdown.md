# ADR-0250: Multi-Agent Reinforcement Pushdown

## Status

Accepted

## Context

Phase 47 的 `ReinforcementPushdownAgent` 是单智能体。Phase 48 升级为
多智能体：跨查询协同决策（加权 Q 聚合 + 反馈闭环），语义层不变。

## Decision

新增 `MultiAgentPushdownCoordinator`：

- 多智能体注册（每查询类型一个 agent）；
- 联邦决策：加权 Q 聚合（权重 = 智能体历史奖励）→ 动作；
- 反馈闭环：执行结果回传对应智能体；
- 与 ReinforcementPushdownAgent / SqlExecutor 联动；
- 语义层与上层 SQL 结果一致（协同只改决策层）。

## Alternatives

1. 单智能体：无法跨查询协同；
2. 全量下推：忽略传输收益；
3. 无反馈闭环：决策不可演进。

## Consequences

优点：跨查询自适应；联邦决策可审计。

缺点：多智能体状态同步开销。

风险：Q 聚合漂移 → 语义层不变 + 护栏兜底。

## Implementation

`sql/coprocessor/MultiAgentPushdownCoordinator` +
`src/test/java/io/tieringkv/sql/coprocessor/MultiAgentPushdownCoordinatorTest`、
`docs/sql/multi-agent-reinforcement-pushdown.md`。
