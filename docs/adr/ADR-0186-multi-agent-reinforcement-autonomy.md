# ADR-0186: Multi-Agent Reinforcement Autonomy

## Status

Accepted

## Context

Phase 38 的强化学习为单智能体原型（TD-067），跨地域策略各自独立；
需要多智能体联合学习共享经验。

## Decision

1. `capacity/ai/MultiAgentAutonomy`：每地域本地 Q + 周期聚合（联邦
   平均/加权）→ 全局权重；
2. 聚合限幅 + 安全上下界 + 审计；
3. 与 ReinforcementAutonomy 联动；
4. 只聚合 Q/权重，禁止放宽安全核心约束；
5. 验收：聚合矩阵 → 全局权重、本地/全局差异、越界拒绝。

## Alternatives

1. 单智能体独立：经验不共享；
2. 中心化训练：隐私/带宽成本。

## Consequences

优点：跨地域经验共享，收敛更快。

缺点：聚合周期需配置。

风险：聚合偏差由限幅与审计兜底。

## Implementation

代码影响范围：`capacity/ai/` + 测试 +
`docs/capacity/multi-agent-autonomy.md`。
