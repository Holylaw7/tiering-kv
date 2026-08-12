# ADR-0173: Cross-Cloud Remote Materialization

## Status

Accepted

## Context

Phase 35/36 物化视图在协调器本地聚合，跨云查询仍需网络往返；需要远端
物化 + 增量同步降低延迟与跨云流量。

## Decision

1. `datamesh/RemoteMaterializationManager`：远端物化定义（云 + 域 +
   聚合）→ 远端落盘 + 增量同步；
2. 增量同步复用 CDC 增量（ADR-0166）；
3. 主权约束：跨驻留物化默认拒绝；
4. 验收：远端物化正确性 + 同步一致性 + 主权拒绝矩阵。

## Alternatives

1. 本地物化：跨云查询延迟高；
2. 全量复制：存储成本高。

## Consequences

优点：远端就近查询，跨云流量低。

缺点：需要同步链路维护。

风险：同步滞后由 stale 标记兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/remote-materialization.md`。
