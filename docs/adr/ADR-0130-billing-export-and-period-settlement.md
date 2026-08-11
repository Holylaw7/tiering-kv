# ADR-0130: Billing Export and Period Settlement

## Status

Accepted

## Context

Phase 29 计量为内存模型。需要周期结算、账单行项目与导出（CSV/JSON），
并与审计关联。

## Decision

新增 `saas/billing/`：

1. `BillingPeriod`：起止 + 冻结语义；
2. `Invoice`：行项目（维度/数量/单价/小计）+ 总价；
3. `InvoiceExporter`：CSV/JSON 导出；
4. 结算：快照 → 冻结 → 导出 → TenantAuditLog 关联。

## Alternatives

1. 实时计费：竞态多；
2. 外部账单系统：依赖第三方。

## Consequences

优点：结算可复现、可审计。

缺点：计量为原型，导出格式待演进。

风险：冻结后新用量需下周期处理。

## Implementation

代码影响范围：`saas/billing/` + 测试 +
`docs/saas/billing-export.md`。
