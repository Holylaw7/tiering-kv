# 跨云数据网格联邦指南（ADR-0152）

## 执行模型

```text
DomainCatalog（域 + RBAC）
  → FederatedPlanner（跨域计划）
  → CloudFederatedExecutor（协调器 + 云分片）
      → 数据主权校验（单驻留要求）
      → 跨云聚合（SUM/COUNT/AVG/MIN/MAX）
```

## 数据主权

- 协调器云与全部分片云必须映射到同一驻留要求；
- 跨驻留边界（如 us ↔ eu）默认抛 SecurityException；
- 未知云按 "default" 处理，与已配置驻留冲突即拒绝。

## JOIN

跨云聚合后可用 `FederatedExecutor.join` 按 key 合并左右域结果。
