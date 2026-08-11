# ADR-0118: SaaS Multi-Tenant Control Plane

## Status

Accepted

## Context

Phase 27 SaaS 为配额原型（ClusterTenant + 校验器）。多租户落地需要
注册、集群生成、隔离与审计。

## Decision

扩展 `saas/`：

1. `TenantRegistry`：租户注册/列表/状态；
2. 租户级 TieringKVCluster 生成（Operator 联动，ADR-0107）；
3. 隔离校验：存储/命名空间唯一性；审计日志（创建/扩容/备份/删除）；
4. 配额动态调整与告警。

## Alternatives

1. 共享集群多命名空间：隔离边界弱；
2. 每租户完整独立部署：成本高。

## Consequences

优点：租户生命周期可管理、可审计。

缺点：真实网络/存储隔离需 K8s 配合（Phase 28 提供模型与演练）。

风险：审计日志需持久化（复用 PITR/CDC 基础设施）。

## Implementation

代码影响范围：`saas/` + 测试 + `docs/saas/multi-tenant-guide.md`。
