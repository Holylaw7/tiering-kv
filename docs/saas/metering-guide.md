# SaaS 计量与市场

Phase 29 · ADR-0124

## 计量

- UsageMeter：REQUESTS / STORAGE_GB / EGRESS_GB 累计 + 周期快照；
- BillingPlan：维度 → 单价；MeteredBilling 计算费用。

## 市场

- ClusterTemplate：规格 → 月费；
- 配额联动：超限降级/告警（Phase 28 配额 + Goal 7 告警）。

## 基准（进程内）

计费计算 1000 次 ≈0ms。

## 限制

- 账单导出/周期结算待 Phase 30；
- 计量为内存模型，持久化接 PITR/CDC（后续）。
