# Phase 58 Review — v4.0 M1 Vector Storage Integration

## 总体结论

v4.0 M1（ADR-0319）完成：向量从内存原型升级为"文件持久化闭环 +
mmap 读取 + SQL 混合检索 + Redis 命令入口"，additive 不破坏 v3.7
冻结协议。全量回归 **14547 tests / 0 failures**（本地），真实 Runner
门禁 6/6 全绿。

## 交付清单

1. **VectorIndexFile**（MAGIC/version/CRC32C + 原子写 temp+fsync+rename；
   损坏/版本/维度不一致明确拒绝）；
2. **VectorIndexStore**（内存 VectorStore + checkpoint / load /
   确定性 HNSW 分层重建）；
3. **VectorIndexMmapReader**（复用 MappedFile + BlockCache，记录级
   缓存，越界/头损坏检测）；
4. **SQL 接线**（SqlIndexRegistry IndexType.SCALAR/VECTOR + dimension；
   IndexAwarePlanner 提示；VectorSqlSearch 维度校验 + 标量过滤）；
5. **向量命令族**（VECTOR.ADD / SEARCH / DEL / LEN；默认 115 命令
   注册表不变，createDefaultWithVector 显式启用；Main 已接线）；
6. **基准报告**（docs/benchmark/phase58-vector-storage-report.md）：
   checkpoint 273K ops/s；20K 向量暴力检索 P50 5.6ms / P99 9.9ms。

## 测试与门禁

- 新增测试：文件 6 + store 5 + mmap 3 + sql 7 + E2E 1 + 命令 6 =
  28 项（surefire 口径）；
- 全量回归 14547/0 failures/6 skipped；
- 真实 Runner：build / test / transaction-e2e × main/develop 6/6；
- 默认注册表 size 115 基线未变（既有 Phase51-56 断言保护）。

## 已知限制（如实记录）

- HNSW 仍为简化原型（分层列表 + 全量扫描），图检索优化列入 v4.0 M2+；
- 向量索引 checkpoint 为手动/命令触发，自动 flush 与多版本 GC 未做；
- 向量命令作用于全局内存索引，跨节点/复制未接线（M3 范围）；
- VECTOR.SEARCH 无 key 维度（全局索引），多集合命名空间未做。

## 后续

- v4.0 M2（多模型编码）：ADR-0320 启动；
- M1 增强项（HNSW 图检索、自动 checkpoint、多集合）按路线图排期。
