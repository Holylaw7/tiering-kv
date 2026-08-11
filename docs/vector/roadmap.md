# Vector Index 路线图（探索原型）

Phase 27 · ADR-0113

## 当前原型

- `VectorStore`：Embedding put/delete/search（暴力余弦）
- topK 检索，跳过空/全零向量

基准（进程内）：500 向量 topK=5 ≈5.5–14.5K ops/s。

## 路线图

| 版本 | 能力 |
| --- | --- |
| v1.2 | HNSW 索引原型、批量导入 |
| v1.3 | 向量 + 标量混合过滤 |
| v2.0 | 向量变更流（CDC 联动）、生产化索引 |

## 边界

暴力检索仅作基线；召回率/延迟与 HNSW 对比在 v1.2 输出。
