# ADR-0148: Data Mesh Federated Query

## Status

Accepted

## Context

分布式 SQL 当前面向单一逻辑库内分片；数据网格需要跨业务域（Domain）
联邦查询：域注册、查询分片、跨域聚合，并保持域级隔离（RBAC）。

## Decision

1. `datamesh/DomainCatalog`：域注册与元数据；
2. `datamesh/FederatedPlanner`：跨域查询 → 分片计划；
3. `datamesh/FederatedExecutor`：跨域聚合（JOIN/SUM/COUNT/AVG）；
4. 域隔离：查询必须通过域授权检查。

## Alternatives

1. 全局大表：破坏域自治；
2. ETL 汇数仓：实时性差。

## Consequences

优点：跨域实时联邦 + 域隔离。

缺点：跨域 JOIN 需分布式执行与聚合。

风险：跨域一致性与性能由测试矩阵约束。

## Implementation

代码影响范围：`datamesh/` + 测试 + `docs/datamesh/federated-query.md`。
