# 数据网格联邦查询指南（ADR-0148）

## 组件

- `DomainCatalog`：域注册 + 域级 RBAC；
- `FederatedPlanner`：跨域查询 → 分片计划（域隔离校验）；
- `FederatedExecutor`：SUM/COUNT/AVG/MIN/MAX + INNER JOIN。

## 示例

```java
Plan plan = planner.plan(new Query("revenue", "SUM",
        List.of("orders", "payments")), "ANALYST");
AggregateResult result = executor.execute(plan,
        shard -> shardExecutor.apply(shard));
List<JoinRow> joined = executor.join(leftRows, rightRows);
```

## 域隔离

规划阶段校验角色；未授权域抛 SecurityException，避免数据越权聚合。
