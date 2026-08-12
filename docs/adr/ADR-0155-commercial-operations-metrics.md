# ADR-0155: Commercial Operations Metrics

## Status

Accepted

## Context

商业化已具备订阅/计费闭环，缺少运营指标（MRR、试用转化、流失）与
告警，无法驱动业务决策。

## Decision

1. `saas/operations/`：MrrCalculator（周期收入）、TrialConversionTracker、
   ChurnDetector（取消率阈值）、CommercialAlert（告警规则）；
2. 与 BillingSubscription / Subscription 状态机联动；
3. 验收：MRR 计算矩阵 + 转化/流失阈值矩阵。

## Alternatives

1. 人工报表：延迟高；
2. 外部 BI：依赖重。

## Consequences

优点：运营指标实时可告警。

缺点：阈值需业务配置。

风险：口径变化由参数化测试兜底。

## Implementation

代码影响范围：`saas/operations/` + 测试 +
`docs/saas/operations-metrics.md`。
