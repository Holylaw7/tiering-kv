# ADR-0229: Multi-Table Join & Window Function Pushdown

## Status

Accepted

## Context

Phase 44 支持单表 JOIN（等值内连接）与 GROUP_BY/ORDER_BY/LIMIT。
Phase 45 需要多表等值连接、窗口函数（ROW_NUMBER/RANK）下推，并为
下推选择提供成本模型。

## Decision

扩展 `CompoundCoprocessorRequest` 与 `CoprocessorExecutor`：

- 多表 JOIN：`joinTables`（N-1 个附加表），按固定顺序链连接；
- 窗口函数：ROW_NUMBER / RANK（按 key 分区、value 排序）；
- `PushdownCostModel`：估算下推收益（本地行数 × 字节 vs 传输成本），
  供 SqlExecutor 选择下推计划；
- 与上层 SQL 结果一致性由等价性测试锁定。

## Alternatives

1. 全部在协调器层执行：读取放大；
2. 仅支持单表 JOIN：无法满足规模化；
3. 无成本模型：下推选择不可解释。

## Consequences

优点：减少跨层数据传输；窗口语义单点维护。

缺点：N 表连接复杂度随表数增长。

风险：窗口函数语义差异需在文档中声明（分区/排序确定性）。

## Implementation

`sql/coprocessor/CompoundCoprocessorRequest`（joinTables + window 参数）、
`CoprocessorExecutor`、`sql/coprocessor/PushdownCostModel` +
`src/test/java/io/tieringkv/sql/coprocessor/MultiTableJoinWindowTest`、
`src/test/java/io/tieringkv/sql/coprocessor/PushdownCostModelTest`、
`docs/sql/multi-table-join-window-pushdown.md`。
