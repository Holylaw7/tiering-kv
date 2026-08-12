# ADR-0190: Adaptive Policy Hardening

## Status

Accepted

## Context

Phase 38 的风险评分只量化不处置；高风险策略不会自动收紧。

## Decision

1. `security/network/AdaptiveHardener`：风险评分阈值 → 自动撤销高风险
   白名单；
2. 加固动作审计 + 回滚；
3. 与 PolicyRiskScorer / PolicyAuditView 联动；
4. 验收：评分阈值矩阵 + 加固/回滚 + 审计。

## Alternatives

1. 仅评分：风险不处置；
2. 无审计自动收紧：不可回滚。

## Consequences

优点：风险自动处置可回滚。

缺点：阈值需配置。

风险：误收紧由审计回滚兜底。

## Implementation

代码影响范围：`security/network/` + 测试 +
`docs/security/adaptive-hardening.md`。
