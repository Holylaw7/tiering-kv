# ADR-0295: SQL/Vector Production Hardening

## Status

Accepted

## Context

SQL 错误语义与 EXPLAIN 不完整；HNSW 无持久化。

## Decision

采用生产化收敛（不改存储内核）：

- `SqlProductionSupport`：统一错误码（语法/未知列/类型）+ EXPLAIN
  完整计划树（scan/join/aggregate/pushdown）；
- HnswIndex 序列化/反序列化（图 + 向量 + 参数）+ 重建校验；
- 混合检索（HNSW + 标量过滤）接入存储。

## Alternatives

1. 重写 SQL/向量：范围失控；
2. 只加测试不改功能：错误不收敛；
3. HNSW 内存态：重启丢失。

## Consequences

优点：语义可测、持久化可用。

缺点：仍为实验分层（完成度基线标注）。

风险：序列化格式需版本化。

## Implementation

`sql/coprocessor/SqlProductionSupport`、`vector/HnswIndex` 序列化 +
`src/test/java/io/tieringkv/sql/SqlProductionHardeningTest.java`、
`src/test/java/io/tieringkv/vector/VectorPersistenceTest.java`。
