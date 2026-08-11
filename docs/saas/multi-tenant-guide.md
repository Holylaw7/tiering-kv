# SaaS 多租户指南

Phase 28 · ADR-0118

## 能力

- TenantRegistry：注册/列表/挂起/删除；
- TenantAuditLog：创建/扩容/备份/删除全审计；
- TenantClusterPlanner：配额内生成 TieringKVCluster（Operator 联动）；
- 配额动态校验（region/存储）。

## 使用

```java
TenantRegistry registry = new TenantRegistry();
registry.register(new ClusterTenant("t1", "prod", 5, 100));
TieringKVClusterSpec spec = new TenantClusterPlanner()
        .plan(tenant, 3, "tiering-kv:1.1.0");
```

## 限制

- 真实网络/存储隔离需 K8s 配合（Phase 28 提供模型与演练）；
- 计费/市场为 Phase 29+。
