# 动态重分片指南

Phase 30 · ADR-0126

## 架构

```text
ShardRouter（routing version + epoch）
  → ReshardPlanner（拆分/合并计划）
  → ShardMigration（逐键迁移 + 校验）
  → commitSwitch（原子切换）/ rollback（回滚）
```

## 能力

- 在线拆分（1→N）/合并（N→1）；
- 迁移期间双写窗口（migrating 标志）；
- 路由版本单调递增，失败回滚恢复原路由；
- 中断安全：剩余键保留在源，不丢失。

## 使用

```java
ShardRouter router = new ShardRouter(2);
router.beginMigration(4);
// 双写窗口…
long version = router.commitSwitch(4);
```

## 限制

- 迁移为逐键模型，真实并行迁移接 Migration 能力（Phase 31 深化）；
- 负载驱动自动重分片为 Phase 31+。
