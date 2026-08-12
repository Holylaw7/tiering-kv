# ADR-0150: Console SaaS Productization

## Status

Accepted

## Context

Phase 33 的控制台 UI 为原型（租户/账单/指标/告警视图），缺少订阅管理、
计费仪表盘与市场自服务下单，商业化闭环无法端到端演示。

## Decision

1. `console/ui/` 扩展：订阅管理视图、计费仪表盘（周期收入/用量趋势）、
   市场自服务下单（MarketplaceCatalog 联动）；
2. `console/api/SaasConsoleApi`：订阅/计费/市场 REST 端点（RBAC）；
3. 验收：视图渲染 + 下单 → 订阅 → 计费闭环 + RBAC 矩阵。

## Alternatives

1. 仅扩展 HTML 视图：无 API 无法被外部控制台消费；
2. 引入前端框架：原型阶段依赖重。

## Consequences

优点：可演示的端到端商业化闭环。

缺点：静态渲染，无实时推送。

风险：订阅与计费状态一致性由状态机测试兜底。

## Implementation

代码影响范围：`console/ui/` + `console/api/` + 测试 +
`docs/console/saas-dashboard.md`。
