# RFC-0001: v4.0 Multi-Model & Production Vector Path

## 摘要

v4.0 主线：SQL/向量从 EXPERIMENTAL 走向 PRODUCT；冷热分层扩展
多模型编码；控制面/多集群联邦深化。

## 动机

完成度基线：SQL/向量为原型；v4.0 需真实查询路径与持久化闭环。

## 设计

1. SQL：错误语义/EXPLAIN 已收敛（Phase 54），v4 补索引与执行接线；
2. 向量：HNSW 已可持久化（Phase 54），v4 补存储接入与混合检索；
3. 多模型编码：类型化值扩展（additive，冻结格式不变）；
4. 多集群：联邦一致性验证器 → 真实跨集群复制接线。

## 备选

1. 只做 SQL：向量继续原型；
2. 只做向量：SQL 停留实验；
3. 维持现状：EXPERIMENTAL 层不收敛。

## 兼容性

v1.0–v3.7 冻结协议不变；扩展 additive + ADR。

## 影响范围

sql/vector/storage/types + gateway + 文档。

## 评审结论

Approved（2026-08-14）：转入 ADR-0318 与 feature/v4-multi-model
分支；阶段一 = SQL 索引接线（SqlIndexRegistry）。
