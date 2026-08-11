# ADR-0136: Billing Rolling Settlement & Multi-Cloud Deployment

## Status

Accepted

## Context

Phase 30 账单为手动冻结/导出。需要周期自动滚动结算；同时 K8s 清单需
多云参数化并支持集群间迁移。

## Decision

1. `saas/billing/BillingScheduler`：周期滚动（月/周/自定义）→ 冻结 →
   导出 → 审计关联；
2. `deploy/multicloud/`：storageClass/ingress/registry 参数化；
3. `CloudMigration`：跨环境数据搬迁（复用复制/迁移能力）。

## Alternatives

1. 手动结算：易漏单；
2. 单云绑定：无法迁移。

## Consequences

优点：结算自动化、部署可移植。

缺点：多云差异需抽象层。

风险：迁移期间一致性需版本屏障。

## Implementation

代码影响范围：`saas/billing/` + `deploy/multicloud/` + 测试 +
`docs/{saas/billing-rolling,deployment/multicloud-guide}.md`。
