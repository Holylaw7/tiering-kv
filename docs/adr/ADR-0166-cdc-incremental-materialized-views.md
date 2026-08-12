# ADR-0166: CDC Incremental Materialized Views

## Status

Accepted

## Context

Phase 35 物化视图为周期全量刷新，热点变更重复聚合；需要 CDC 增量刷新
降低延迟与计算成本。

## Decision

1. `datamesh/CdcMaterializedViewRefresher`：变更流（key + 版本）→
   增量聚合更新；
2. 与 Phase 26 CDC 能力联动（增量事件源）；
3. 增量失败回退全量刷新并标记 stale；
4. 验收：增量正确性矩阵（插入/更新/删除）+ 回退矩阵。

## Alternatives

1. 周期全量：延迟高、计算冗余；
2. 无回退：增量失败导致数据错误。

## Consequences

优点：增量刷新低延迟、低开销。

缺点：实现复杂度上升。

风险：增量错误由回退全量 + stale 标记兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/cdc-materialized-view.md`。
