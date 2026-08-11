# SaaS 账单导出与周期结算

Phase 30 · ADR-0130

## 模型

```text
BillingPeriod（起止 + 冻结）
  → Invoice（行项目：type/quantity/unitPrice/subtotal）
  → InvoiceExporter（CSV / JSON）
```

## 结算

计量快照 → 冻结周期 → 导出 → TenantAuditLog 关联（Phase 28）。

## 基准（进程内）

账单导出 5.9K–143K ops/s。

## 限制

- 周期自动滚动待 Phase 31；
- 账单持久化接 PITR/CDC（后续）。
