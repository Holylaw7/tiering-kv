# ADR-0154: Enterprise Observability: Tracing & Cost Attribution

## Status

Accepted

## Context

可观测性目前为指标（Prometheus/INFO），缺少跨 RPC 追踪与按
租户/域/云的成本归因，无法定位链路与成本。

## Decision

1. `observability/tracing/`：Span/Trace 上下文（跨 RPC 传播）+
   TraceSampler + TraceExporter（JSON）；
2. `observability/cost/`：CostAttribution（租户/域/云 → 资源成本）；
3. 与 Phase28Metrics / Prometheus 导出联动；
4. 验收：跨 RPC 追踪完整链路、成本归因矩阵。

## Alternatives

1. 仅指标：无法关联单请求链路；
2. 外部追踪系统（Jaeger）：依赖重。

## Consequences

优点：链路可定位、成本可归因。

缺点：采样与导出需配置。

风险：追踪只观测，不修改事务/Raft 状态机。

## Implementation

代码影响范围：`observability/` + 测试 +
`docs/observability/tracing-cost.md`。
