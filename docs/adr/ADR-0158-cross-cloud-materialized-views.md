# ADR-0158: Cross-Cloud Materialized Views

## Status

Accepted

## Context

跨云联邦每次查询实时聚合（125K–666K ops/s），热点查询重复计算；
需要预聚合物化视图降低延迟与跨云流量。

## Decision

1. `datamesh/MaterializedView`：视图定义（域 + 聚合 + 刷新周期）；
2. `datamesh/MaterializedViewManager`：创建/刷新/失效/查询；
3. 与 CloudFederatedExecutor 联动（跨云预聚合）；
4. 陈旧数据必须有标记（stale 标志），禁止无标记返回；
5. 验收：刷新一致性矩阵 + 失效/查询正确性。

## Alternatives

1. 实时聚合：重复计算，跨云流量高；
2. 全量复制：存储成本高。

## Consequences

优点：热点查询延迟低、跨云流量低。

缺点：存在刷新延迟（最终一致）。

风险：陈旧数据由 stale 标记与失效机制兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/materialized-view.md`。
