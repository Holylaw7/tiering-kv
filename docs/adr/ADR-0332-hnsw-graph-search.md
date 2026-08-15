# ADR-0332: HNSW Graph Search

## Status

Accepted

## Context

M1 限制：HnswIndex 为"分层列表 + 全量扫描"简化原型，20K 向量检索
P99 9.9ms（暴力口径）。目标：真实多层图 + 贪心搜索 + 候选扩展，
P99 < 1ms（20K × 64 维）。

## Decision

- 重写 `HnswIndex`：多层图（每层节点子集 + 邻居边）+ 层级随机
  （mL）+ 插入时逐层贪心连接（efConstruction 候选，双向边）；
- 搜索：从入口节点贪心下降到层 0，目标层 efSearch 候选扩展
  （优先级队列按余弦距离），返回 topK；
- 保留 build/serialize/deserialize API；序列化格式扩展（参数 +
  vectors + 层邻居边 + 入口节点）；
- VectorStore 暴力检索保留（正确性基准/小数据集），HnswIndex 用于
  检索路径；
- 验收：召回率 ≥0.9（与暴力 topK 对比）+ 20K 检索 P99 <1ms。

## Alternatives

1. 保持暴力扫描：正确但延迟线性；
2. 引入第三方向量库（Lucene/FAISS）：依赖重、冻结协议无关。

## Consequences

优点：检索延迟对数级下降，自研可展示。

缺点：近似检索（召回 <100%），图构建耗时。

风险：参数（efConstruction/efSearch/mL）影响召回/性能平衡——基准
矩阵记录。

## Implementation

`vector/hnsw/HnswIndex.java` 重写 + 测试（构建/搜索/召回/序列化）。
