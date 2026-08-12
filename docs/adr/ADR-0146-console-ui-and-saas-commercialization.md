# ADR-0146: Console UI & SaaS Commercialization

## Status

Accepted

## Context

控制台当前只有 REST API（ADR-0139），无 Web 视图；SaaS 侧有租户、计量、
周期账单，但缺少订阅生命周期、市场目录与计费联动，商业化闭环未闭合。

## Decision

1. `console/ui/`：静态 HTML 视图（租户/集群/账单/指标/告警）+ REST
   调用 + RBAC 门控（ADMIN/READ）；
2. `saas/commerce/`：Subscription（active/trial/canceled）、
   MarketplaceCatalog（模板 + 定价）、BillingSubscription（周期联动）；
3. 计费复用 Phase 31 BillingScheduler，订阅状态机参数化验收。

## Alternatives

1. 前端框架（React/Vue）：依赖重，原型阶段不必要；
2. 仅 API 无 UI：无法演示自服务闭环。

## Consequences

优点：可演示的自服务商业化闭环。

缺点：静态 UI 不做实时推送。

风险：订阅与计费状态一致性由状态机测试兜底。

## Implementation

代码影响范围：`console/ui/` + `saas/commerce/` + 测试 +
`docs/{console/ui-guide,saas/commercialization}.md`。
