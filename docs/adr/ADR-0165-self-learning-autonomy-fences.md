# ADR-0165: Self-Learning Autonomy Fences

## Status

Accepted

## Context

Phase 35 的自治围栏为静态配置（日预算/单步上限/地域上限），不随执行
结果调整；连续失败或成功不会改变策略，无法自适应。

## Decision

1. `capacity/ai/SelfLearningFence`：记录执行结果 → 调整围栏参数
   （限幅内自适应）；
2. 学习规则：连续成功 → 温和放宽；连续失败/回滚 → 收紧并熔断；
3. 护栏：参数变化限幅、安全上下界、审计日志；
4. 只调整策略参数，禁止放宽安全核心约束。

## Alternatives

1. 静态围栏：无法自适应；
2. 无限放宽：风险不可控。

## Consequences

优点：围栏随运行历史自适应。

缺点：需要结果反馈输入。

风险：学习偏差由上下界与审计兜底。

## Implementation

代码影响范围：`capacity/ai/` + 测试 +
`docs/capacity/self-learning-autonomy.md`。
