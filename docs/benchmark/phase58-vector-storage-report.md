# Phase 58 — v4 M1 Vector Storage Benchmark Report

## 口径

- 环境：本地 Windows / JDK 17（开发机基线；真实 Runner 基线随 CI 门禁
  记录）；单线程，无持久化 fsync 计数（checkpoint 含 fsync）；
- 数据：20,000 条 64 维向量（值 0–0.99 均匀）；
- 写入：`VectorIndexStore.checkpoint`（temp + fsync + atomic rename）；
- 读取：`VectorIndexMmapReader` 全量 mmap 遍历 + `BlockCache`（4096 槽）
  预热后，暴力余弦 top-K 查询 2,000 次取 P50/P99（M1 检索正确性基线，
  HNSW 图检索优化列入 v4.0 M2 之后的性能项）。

## 结果

| 指标 | 值 |
| --- | --- |
| checkpoint 写入吞吐 | 273,117 ops/s（20K × 64 维） |
| mmap 全量读取 | 与内存加载结果一致（E2E 断言） |
| 检索 P50（20K 向量暴力） | 5.601 ms |
| 检索 P99（20K 向量暴力） | 9.879 ms |

## 结论

- 文件持久化闭环成立：写入原子 + CRC 校验 + 加载/重建一致；
- mmap 读取路径与内存检索结果一致，BlockCache 热读命中；
- 20K 向量暴力检索 P99 < 10ms，正确性基线达标；
- 后续：HNSW 图检索（替代暴力扫描）、向量与标量 join、多版本索引 GC
  列入 v4.0 M2/M3。

## 复现

```bash
mvn -Dsurefire.excludedGroups= -Dtest=VectorStorageBenchmarkTest \
  -DfailIfNoTests=false test
```
