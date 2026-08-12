# ADR-0180: Reinforcement-Learning Autonomy

## Status

Accepted

## Context

Phase 37 的多目标围栏权重为人工配置，不随长期结果进化；需要强化学习
原型自动调整权重。

## Decision

1. `capacity/ai/ReinforcementAutonomy`：动作（放宽/收紧/保持）× 回报
   （成本 × 风险 × SLO）→ 权重更新（简化 Q 学习）；
2. 学习率/折扣因子可配置，权重变化限幅；
3. 与 MultiObjectiveFence 联动；
4. 只调整策略权重，禁止放宽安全核心约束；
5. 验收：回报矩阵 → 权重变化方向、越界拒绝。

## Alternatives

1. 人工权重：不随环境进化；
2. 无约束学习：权重漂移风险。

## Consequences

优点：权重自适应，长期优化。

缺点：学习过程需要回报信号。

风险：学习偏差由限幅与审计兜底。

## Implementation

代码影响范围：`capacity/ai/` + 测试 +
`docs/capacity/reinforcement-autonomy.md`。
