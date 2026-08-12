# ADR-0210: Coprocessor SQL Pushdown

## Status

Accepted

## Context

SQL 聚合在协调层执行，数据全量上行；需要算子下推到存储层。

## Decision

1. `sql/coprocessor/CoprocessorRequest`：算子 + 范围 + 谓词；
2. `sql/coprocessor/CoprocessorExecutor`：存储层执行 → 结果集；
3. 与 SqlExecutor / 分布式执行联动；
4. 验收：下推结果与上层 SQL 一致 + 谓词矩阵。

## Alternatives

1. 全量上行：带宽/延迟高；
2. 黑盒下推：结果不可校验。

## Consequences

优点：下推减少上行数据。

缺点：算子集有限。

风险：结果偏差由一致性测试兜底。

## Implementation

代码影响范围：`sql/coprocessor/` + 测试 +
`docs/sql/coprocessor-pushdown.md`。
