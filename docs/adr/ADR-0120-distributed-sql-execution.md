# ADR-0120: Distributed SQL Execution

## Status

Accepted

## Context

Phase 28 SQL 引擎为单机内存执行（Hash Join / 聚合）。跨 Region 查询需要
把 scan/filter/partial aggregate 下推到 Region，Coordinator 合并。

## Decision

新增 `sql/distributed/`：

1. `ShardPlanner`：按 key 范围/谓词生成分片计划（Region 下推）；
2. `PartialAggregate` + `MergeAggregate`：两阶段聚合；
3. `MergeJoin`：广播/分区 join 策略；
4. `DistributedExecutor`：协调各 Region 执行并合并结果。

## Alternatives

1. 全量拉取到协调器：数据放大；
2. 不分区：无法跨 Region。

## Consequences

优点：查询下推、跨 Region 可执行、基准可量化。

缺点：两阶段聚合语义需严格测试。

风险：Region 故障导致部分结果，需错误传播。

## Implementation

代码影响范围：`sql/distributed/` + 测试 +
`docs/sql/distributed-execution.md`。
