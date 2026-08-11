# CRDT 设计

Phase 28 · ADR-0114

## 原语

| 类型 | 语义 | 合并 |
| --- | --- | --- |
| LwwRegister | 后写胜出（ts + node） | 按胜出规则 |
| GCounter | 只增计数 | 每节点取 max |
| GSet | 只增集合 | 并集 |
| OrSet | 可增删集合 | add/remove tag 并集 |

## 性质

- 交换律/结合律/幂等：任意合并顺序收敛；
- 删除必须携带 add 的 tag（OrSet）；
- 全零/空向量不参与向量检索（HNSW）。

## 验证

`BidirectionalReplicationTest` / `Phase28CrdtEdgeTest`：多节点合并、
同分平局、并发冲突收敛、环回抑制。
