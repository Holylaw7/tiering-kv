# ADR-0117: HNSW and Hybrid Search

## Status

Accepted

## Context

Phase 27 向量检索为暴力基线（5.5–14.5K ops/s）。生产需要索引检索与
向量+标量混合过滤，且召回率可对比。

## Decision

新增 `vector/hnsw/`：

1. HNSW 原型：层级图构建 + 贪心搜索 + 批量导入；
2. `HybridSearch`：向量相似度 + 标量谓词（复用 SQL 过滤语义）；
3. 召回率/延迟对比（HNSW vs 暴力，如实记录）；
4. 与 CDC 联动：向量变更自动增量索引。

## Alternatives

1. 保持暴力检索：无法满足生产延迟；
2. 引入外部向量库：增加依赖与运维面。

## Consequences

优点：延迟降低、召回率可量化。

缺点：HNSW 为原型级实现，参数（M/efConstruction）需校准。

风险：删除/更新索引一致性需测试覆盖。

## Implementation

代码影响范围：`vector/hnsw/` + `vector/HybridSearch` + 测试 +
`docs/vector/hnsw-report.md`。
