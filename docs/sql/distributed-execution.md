# 分布式 SQL 执行

Phase 29 · ADR-0120

## 架构

```text
ShardPlanner（分片计划）→ Region 下推（scan/filter/partial aggregate）
  → MergeAggregate（COUNT/SUM/AVG）
  → MergeJoin（去重合并）
```

## 能力

- 按 key 前缀切分 + Region 轮询分配；
- 两阶段聚合（partial + merge）；
- 分布式 JOIN 结果合并（去重）；
- `DistributedExecutor` 协调执行。

## 基准（进程内）

- 分片计划 7.7–27K ops/s；合并聚合 1K partials ≈0–7ms；
- JOIN 1K×1K ≈11ms。

## 限制

- 分片为前缀哈希模型，动态重分片待 Phase 30；
- Region 故障传播为 ERROR（Phase 30 深化）。
