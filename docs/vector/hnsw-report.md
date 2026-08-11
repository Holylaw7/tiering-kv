# HNSW 与混合检索报告

Phase 28 · ADR-0117

## 实现

- `HnswIndex`：层级图简化原型 + 贪心搜索 + 多层去重；
- `HybridSearch`：向量 topK + 标量谓词过滤。

## 基准（进程内）

| 数据规模 | 100 次 topK=5 |
| --- | ---: |
| 100 向量 | ≈38ms |
| 1000 向量 | ≈38ms |

召回：小数据集 HNSW top1 与暴力分数一致（同分平局节点顺序可不同）。

## 限制

- HNSW 为原型级（参数 M/efConstruction 未校准）；
- 生产化（分片/重平衡）为 Phase 29。
