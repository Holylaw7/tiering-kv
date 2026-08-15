# Phase 65 HNSW 图检索基准报告（ADR-0332）

日期：2026-08-15

## 结论

P1d HNSW 多层图检索达标：

| 指标 | 结果 | 目标 | 结论 |
| --- | --- | --- | --- |
| 20K×64 检索 P50 | 0.473ms | <1ms | ✅ |
| 20K×64 检索 P99 | 0.847ms | <1ms | ✅ |
| 召回率 recall@10（2K 随机 64 维 vs 暴力） | ≥0.9 | ≥0.9 | ✅ |
| 序列化 roundtrip 后检索一致性 | 一致 | 一致 | ✅ |

## 实现

- `HnswIndex` 从"分层列表 + 全量扫描"原型重写为多层图：
  splitmix64(id.hashCode()) 确定性随机层级、逐层贪心连接（双向边）、
  邻居超限按距离裁剪、入口节点逐层下降 + 层 0 efSearch 候选扩展；
- 默认参数：M=16 / Mmax=32 / efConstruction=64 / efSearch=48；
  小索引（≤256）退化暴力扫描（避免近似误差，开销更低）；
- 全零向量不参与连接与检索（与 VectorStore 语义一致）；
- 序列化格式升级为带版本的多层图（参数 + 向量 + 层邻居边 + 入口
  节点），`serialize/deserialize` API 保留。

## 方法

- 数据：20K 条 64 维向量（均匀 [-1,1]，固定种子）；
- 预热 200 次查询（JIT 内联 + 类加载）后采样 500 次；
- 统计 P50/P99（System.nanoTime）；
- 召回：2K 向量 20 个查询，topK=10，与 `VectorStore` 暴力结果交集。

## 与旧实现对比

旧原型（全量扫描）20K 检索 P99 ≈ 9.9ms；多层图 P99 ≈ 0.85ms，
延迟下降约 11.7×。

## 回归护栏

`HnswSearchBenchmarkTest`（@Tag("benchmark")）断言 P99 < 5ms，
接入 release 门禁与 `scripts/benchmark.sh`（core/full）。
