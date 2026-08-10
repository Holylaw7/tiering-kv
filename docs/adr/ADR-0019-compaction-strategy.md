# ADR-0019: Compaction Strategy

## Status

Accepted

## Context

SSTable 随 flush/迁移增多后，读需要查多表、空间含重复与 tombstone，必须合并。
候选：

- **Size-Tiered**：表数/体积达阈值即合并若干表；实现简单，但合并粒度不精细；
- **Leveled**：按层级 L0..Ln 组织，逐层合并；读放大小、空间放大可控，但实现
  与调度复杂；

## Decision

Phase 5 采用 **Size-Tiered 触发 + 全量合并（latest-wins）**：

1. 触发：SSTable 数量 ≥ `compactionThreshold`（默认 8）时执行；
2. 输入：全部现有表（保证全局键序正确，避免"部分合并导致 tombstone 非最新"）；
3. 合并规则（多路归并，表序 = 创建序，新表优先）：
   - 重复键：最新表的条目胜出；
   - tombstone：若最新条目为 tombstone，键整体移除（不写入输出）；
   - 过期 TTL：`expireTimestamp <= now` 的条目直接丢弃；
4. 输出：单一新 SSTable，删除输入文件，原子更新 Manifest；
5. 写放大代价明确记录：全量合并为 O(总数据)，Phase 7 评估 leveled 分层。

## Alternatives

1. Leveled Compaction：读放大更优，但 L0 重叠语义与多级调度复杂，留 Phase 7
   （届时新 ADR）；
2. 部分合并（最小文件优先）：减少写放大，但可能让 tombstone 覆盖错误，
   需要跨表版本仲裁，Phase 5 不做。

## Consequences

**优点：** 正确性简单可证（latest-wins 全局一致）；实现与测试成本可控。
**缺点：** 写放大 = 全量数据；触发阈值需调优。
**风险：** 频繁全量合并拖慢写入 → 阈值配置化 + metrics（Phase 9）。

## Implementation

- `CompactionManager` + `CompactionTask`（多路归并）+ `ColdStorageEngine.compact`；
- 规则与 `DiskIterator`/`MergingIterator` 复用。
