# ADR-0139: Console REST Service

## Status

Accepted

## Context

Phase 31 ConsoleApi 为方法调用。需要 HTTP 服务（/tenants /metrics
/alerts）+ 令牌鉴权 + JSON。

## Decision

新增 `console/rest/`：

1. `ConsoleRestServer`：JDK HttpServer + 路由 + JSON；
2. 令牌头校验（RBAC，ADR-0110）；
3. 自服务：租户创建集群（TenantClusterPlanner 联动）。

## Alternatives

1. 引入 Web 框架：依赖重；
2. 仅 API 模型：无法集成。

## Consequences

优点：HTTP 集成可用、可测试。

缺点：JDK HttpServer 为轻量实现。

风险：并发与 TLS 由部署层兜底。

## Implementation

代码影响范围：`console/rest/` + 测试 +
`docs/console/rest-service.md`。
