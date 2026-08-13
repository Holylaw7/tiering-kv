# Vector Persistence

## HNSW 持久化

- `serialize()`：maxLevel + 各层嵌入（id + 浮点向量）；
- `deserialize()`：重建图 + 向量 + 参数；
- 重建后 search 结果与原始一致（矩阵验证）。

## 限制

- 简化原型分层（完成度基线 EXPERIMENTAL）；混合检索接入 Phase 55
  深化。
