# ADR-0017: Cold Storage Strategy Selection

## Status

Accepted

## Context

冷层需要磁盘存储引擎。候选：

- **Bitcask**：追加日志 + 内存索引；写吞吐高、实现简单；但空间放大、merge
  复杂、无范围查询；
- **LSM-Tree**：有序 SSTable + 顺序写 + compaction；支持范围查询与空间回收，
  实现复杂；
- **B+Tree**：原地更新、读友好；但随机写、实现成本最高，与"顺序写优先"的
  存储目标不符。

## Decision

采用 **LSM 风格冷层 + 现有 WAL 充当追加日志（Bitcask Log 角色）**：

```text
WAL（追加日志，Phase 4 已交付）
  → MemTable（热层）
  → Flush → SSTable（有序、不可变）
  → Manifest + Compaction（空间回收）
```

1. 冷层 = 有序 SSTable 集合 + Manifest（ADR-0018）；
2. 单条迁移写入（淘汰路径）先入 ColdStorageEngine 的 pending 缓冲，达阈值后
   落 SSTable（避免每个迁移键生成一个文件）；
3. 读取：pending → 新表 → 旧表（Bloom → Index → Block）；
4. 压缩：size-tiered 触发 + Phase 5 全量合并（latest-wins，ADR-0019）；
5. Bitcask 的"内存索引 + merge"角色由 BlockIndex + Compaction 承担，
   不单独实现 Bitcask 文件格式。

## Alternatives

1. 纯 Bitcask：实现简单，但无范围查询且 merge 与冷热分层语义重叠；
2. 纯 LSM（含独立 WAL）：等价，但 WAL 已存在，无需重复建设；
3. B+Tree：读最优，但随机写与实现成本不匹配当前阶段。

## Consequences

**优点：** 顺序写、读放大可控（Bloom）、空间回收（compaction）、与 MemTable
有序结构天然衔接。
**缺点：** 实现面大（writer/reader/index/bloom/compaction/manifest）。
**风险：** 全量合并的写放大 → Phase 5 用阈值触发，Phase 7 评估 leveled。

## Implementation

- `io.tieringkv.storage.cold`：SSTable / Writer / Reader / Block / BlockIndex /
  BloomFilter / Manifest / CompactionManager / DiskIterator / ColdStorageEngine；
- FlushManager 对接 MemTable 与 WAL checkpoint。
