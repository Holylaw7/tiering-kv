# ADR-0319: v4 M1 Vector Storage Integration

## Status

Accepted

## Context

v4.0 M1（RFC-0001 / docs/planning/v4-roadmap.md）目标：把向量从
"内存原型 + byte[] 序列化"提升为"文件持久化闭环 + mmap 读取 +
SQL 混合检索"。现状：

- `VectorStore`：内存 Map + 暴力余弦检索（ADR-0113）；
- `HnswIndex.serialize()`：无 magic/version/CRC 的内存 byte[] 格式；
- `HybridSearch`：仅支持 id 谓词过滤，未接 SQL 层；
- v4 阶段一已交付 `SqlIndexRegistry` + `IndexAwarePlanner`（标量列索引）；
- 项目已有可复用基础设施：`MappedFile`（mmap，ADR-0026）、
  `BlockCache`（LRU + DirectByteBuffer 池）、WAL CRC32C 惯例。

## Decision

### 1. 向量索引文件格式 `VectorIndexFile`

独立文件格式（不混入 SSTable，保持冷热存储与向量索引生命周期解耦）：

```text
MAGIC  "TVIF"（4B）
VERSION u8（当前 1）
MAX_LEVEL u32
DIM u32
ENTRY_COUNT u64（层 0 去重后的唯一向量数）
PAYLOAD：逐层记录（层数 u32 + 每条：idLen u16 + id + dim u32 + float[]）
CRC32C u32（覆盖 MAGIC..PAYLOAD 全部字节）
```

写入：临时文件 + fsync + 原子 rename（防半写文件）；加载：全量校验
CRC 后重建内存索引；损坏 → 明确异常，不静默使用。

### 2. mmap 读取 + BlockCache

- 读取复用 `MappedFile`（READ_ONLY 映射，GC 解除映射，无 Unsafe）；
- `VectorIndexMmapReader`：mmap 文件 → 按记录偏移解码 Embedding；
- 解码结果缓存复用 `BlockCache`（CacheKey 以索引文件版本为 tableId、
  记录偏移为 blockOffset），降低热查询解码开销；
- 全量加载路径（`VectorIndexStore.load`）与 mmap 随机读路径并存：
  前者用于索引重建，后者用于热检索。

### 3. SQL 混合检索接线

- `SqlIndexRegistry.Index` 扩展索引类型（`SCALAR`/`VECTOR`）与维度，
  additive：现有标量注册语义不变；
- `IndexAwarePlanner` 输出含索引类型的 `PlanHint`；
- 新增 `VectorSqlSearch`：`search(store, query, topK, sqlPredicate)`
  —— 先向量 top-K 候选，再应用标量谓词过滤（SQL WHERE 列映射到
  embedding id/元数据谓词），与现有 `HybridSearch` 语义一致但显式
  面向 SQL 计划。

### 4. 一致性

- 向量写入：内存索引先行（低延迟），后台/显式 `checkpoint` 落盘索引
  文件（原子 rename + CRC）；重启时加载最新索引文件；
- 单文件版本号递增，旧文件保留待归档（M2 再决定多版本/GC）；
- 不修改 Raft/MVCC/事务状态机，协议与存储格式 v1 冻结不变。

## Alternatives

1. 复用 SSTable 格式存放向量：耦合冷热存储与索引生命周期，flush/
   compaction 语义不匹配向量全量重建需求；
2. 纯 FileChannel + heap byte[]：放弃 mmap 页缓存收益（项目 ADR-0026
   已验证 mmap 1.8–2.1x）；
3. 无文件格式，仅保留 byte[] 序列化：无法满足崩溃恢复/大索引加载。

## Consequences

优点：

- 向量索引获得文件级持久化与 CRC 校验，可重启恢复；
- 复用 mmap/BlockCache 基建，读取路径与冷存储一致；
- SQL 接线 additive，不破坏 v3.7 冻结协议。

缺点：

- 索引文件与存储数据双份生命周期，需要 checkpoint 纪律；
- HNSW 本身仍是简化原型（分层列表 + 全量扫描），M1 不重写图算法。

风险：

- 大索引 mmap 映射占用虚拟地址空间：M1 以单文件 + 版本化滚动规避；
- 向量与标量 join 语义：M1 仅覆盖"同 id 谓词过滤"，跨表 join 留 M2。

## Implementation

包结构（additive）：

```text
src/main/java/io/tieringkv/vector/indexfile/  VectorIndexFile / VectorIndexStore
src/main/java/io/tieringkv/vector/io/          VectorIndexMmapReader
src/main/java/io/tieringkv/vector/sql/         VectorSqlSearch
src/main/java/io/tieringkv/sql/                SqlIndexRegistry（IndexType 扩展）
```

测试：文件 roundtrip / CRC 损坏检测 / 原子写 / mmap 读取一致性 /
混合检索正确性 / CRUD 持久化 E2E；基准：向量索引文件写读 + 混合检索
（docs/benchmark/phase58-vector-storage-report.md）。

关联文档：docs/planning/v4-roadmap.md（M1 状态更新）、
.codex/tasks/phase58-v4-m1-vector-storage.md。
