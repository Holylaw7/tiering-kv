# ADR-0215: Multi-Operator Coprocessor Pushdown

## Status

Accepted

## Context

Phase 42 的 Coprocessor 为单算子下推（TD-080）；需要 FILTER +
PROJECT + AGGREGATE 联合下推。

## Decision

1. `sql/coprocessor/CompoundCoprocessorRequest`：算子链（filter →
   project → aggregate）；
2. 与 CoprocessorExecutor / SqlExecutor 联动；
3. 验收：算子链矩阵 + 与上层 SQL 一致。

## Alternatives

1. 单算子：多次 RTT；
2. 黑盒下推：结果不可校验。

## Consequences

优点：一次下推完成多算子。

缺点：算子组合需校验。

风险：结果偏差由一致性测试兜底。

## Implementation

代码影响范围：`sql/coprocessor/` + 测试 +
`docs/sql/multi-operator-pushdown.md`。
