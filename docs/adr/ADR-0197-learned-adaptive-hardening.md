# ADR-0197: Learned Adaptive Hardening

## Status

Accepted

## Context

Phase 39 的自适应加固阈值为静态配置；风险 → 动作映射不随历史结果进化。

## Decision

1. `security/network/LearnedHardener`：风险评分 × 历史结果 → 阈值
   自进化（简化学习）；
2. 阈值变化限幅 + 审计 + 回滚；
3. 与 AdaptiveHardener / PolicyRiskScorer 联动；
4. 验收：学习矩阵 → 阈值变化、越界拒绝。

## Alternatives

1. 静态阈值：不随环境进化；
2. 无约束学习：阈值漂移。

## Consequences

优点：阈值自适应，风险处置更准。

缺点：需要结果反馈。

风险：学习偏差由限幅与审计兜底。

## Implementation

代码影响范围：`security/network/` + 测试 +
`docs/security/learned-hardening.md`。
