# ADR-0007: MemTable Data Structure Selection

## Status

Accepted

## Context

Phase 2 需要实现内存热层（MemTable），替代 Phase 1 的 `ConcurrentHashMap`
占位实现。候选结构需满足：

- 点读/点写接近 O(1) 或 O(logN)；
- 支持有序迭代，为未来 LSM/SSTable 生成、范围扫描做准备；
- 支持 tombstone（DELETE 标记），便于 WAL / Snapshot / Flush；
- 并发友好（配合 ADR-0008 的分段锁）。

## Decision

采用 **SkipList（LevelDB MemTable 风格）+ 64 段分片**：

1. 每段一个自研 `SkipList`（`storage/memory/SkipList`），键为二进制安全的
   `byte[]`，按无符号字典序排序（`Arrays.compareUnsigned`）；
2. 段数量固定 64（2 的幂，哈希取掩码定位段），段内结构由分段锁保护
   （ADR-0008）；
3. 全局迭代器将 64 个有序段做归并（PriorityQueue 多路合并），对外呈现全局
   有序键空间；
4. 跳表节点直接承载 `KeyValueEntry`（含版本、TTL、tombstone），为后续
   WAL / LSM flush 保留完整元数据。

## Alternatives

1. **ConcurrentHashMap**：实现简单，但无序，无 range scan，flush 前必须全量
   排序，不利于未来 SSTable 生成；
2. **平衡树（TreeMap / B-Tree）**：同样有序，但自研平衡树实现与调试成本高于
   跳表；跳表在点操作、迭代与并发演进（Phase 7 无锁候选）上更成熟；
3. **直接使用 ConcurrentSkipListMap**：可用但缺少自定义 Entry/版本语义，且
   与"从零自研"约束不符。

## Consequences

**优点：** 有序键空间、O(logN) 点操作、与 LSM 天然衔接、段间可并行。
**缺点：** 节点平均多 ~2 个前向指针（内存开销略高于哈希表）。
**风险：** 哈希分布不均导致热点段 → 段数与 FNV-1a 哈希固定，Phase 7 用
metrics 观测后按需调整。

## Implementation

- `io.tieringkv.storage.StorageEngine`：存储 SPI；
- `io.tieringkv.storage.memory`：MemTable、SkipList、KeyValueEntry、Version、
  Iterator、MemoryManager、TTLManager；
- Phase 2 交付；Phase 5 LSM flush 直接消费有序迭代器。
