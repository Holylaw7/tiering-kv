# ADR-0181: Materialized View Lifecycle Management

## Status

Accepted

## Context

远端物化视图无 TTL/归档，长期占用远端存储；需要生命周期管理。

## Decision

1. `datamesh/MaterializedViewLifecycle`：TTL 过期判定 + 归档（快照
   导出）+ 删除；
2. 与 RemoteMaterializationManager 联动；
3. 归档可恢复；
4. 验收：TTL 矩阵 + 归档/恢复 + 过期清理。

## Alternatives

1. 永久保留：存储膨胀；
2. 直接删除：不可恢复。

## Consequences

优点：存储可控、归档可恢复。

缺点：生命周期配置需维护。

风险：TTL 误删由归档兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/materialized-view-lifecycle.md`。
