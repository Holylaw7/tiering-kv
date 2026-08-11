# ADR-0124: SaaS Metering and Marketplace

## Status

Accepted

## Context

Phase 28 SaaS 完成租户/配额/审计。商业化需要计量（请求/存储/流量）、
计费计划与规格市场。

## Decision

扩展 `saas/`：

1. `UsageMeter`：请求/存储/出向流量计量（累计 + 周期快照）；
2. `BillingPlan`：计量维度 → 单价；`MeteredBilling` 计算费用；
3. `ClusterTemplate`：规格目录（region/存储 → 定价）；
4. 配额联动：超限自动降级/告警。

## Alternatives

1. 外部计费系统：依赖第三方；
2. 固定定价：无法差异化。

## Consequences

优点：计量/计费可测试、配额降级可演练。

缺点：计量为原型（账单导出待 Phase 30）。

风险：计量精度需参数化验证。

## Implementation

代码影响范围：`saas/` + 测试 + `docs/saas/metering-guide.md`。
