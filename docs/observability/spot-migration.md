# Spot 中断迁移指南（ADR-0183）

## 使用

```java
SpotMigrationPlanner planner = new SpotMigrationPlanner(2.0);
Optional<MigrationPlan> plan = planner.plan(
        "t1", "aws-us", options,
        new SpotTask("t1", "us", 10, false), policy);
```

## 语义

- 排除中断云；按期望成本选备用云；
- 主权 / 配额 / SLO 约束不变；
- 相同输入 → 相同计划（幂等）；
- 无备用云 → empty（调用方按策略处理）。
