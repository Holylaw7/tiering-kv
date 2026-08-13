# ADR-0236: Window Function Family & Dynamic Pushdown

## Status

Accepted

## Context

Phase 45 支持 ROW_NUMBER/RANK 窗口函数与静态成本模型。Phase 46 需要
窗口函数全族（LAG/LEAD/SUM OVER/COUNT OVER/AVG OVER）与运行时动态
下推（基于历史执行统计）。

## Decision

扩展 `CompoundCoprocessorRequest` 与 `CoprocessorExecutor`：

- 窗口函数全族：LAG/LEAD（偏移取值）、SUM/COUNT/AVG OVER（分区聚合）；
- `DynamicPushdownPlanner`：历史执行统计（吞吐/传输比）→ 动态下推
  决策，供 SqlExecutor 选择计划；
- 与上层 SQL 结果一致性由等价性测试锁定；
- 固定链顺序不变（… → WINDOW → ORDER_BY → LIMIT）。

## Alternatives

1. 仅静态成本模型：无法适应运行时波动；
2. 全量下推：忽略传输收益；
3. 全族窗口仅在协调器执行：读取放大。

## Consequences

优点：窗口语义单点维护；下推决策可解释、自适应。

缺点：LAG/LEAD 需要分区内有序访问（内存缓存）。

风险：动态决策需统计窗口校准，避免抖动。

## Implementation

`sql/coprocessor/CompoundCoprocessorRequest`（窗口全族）、
`CoprocessorExecutor`、`sql/coprocessor/DynamicPushdownPlanner` +
`src/test/java/io/tieringkv/sql/coprocessor/WindowFunctionFamilyTest`、
`src/test/java/io/tieringkv/sql/coprocessor/DynamicPushdownPlannerTest`、
`docs/sql/window-function-family-dynamic-pushdown.md`。
