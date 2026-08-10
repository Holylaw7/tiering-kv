# ADR-0028: SSTable Block Cache Strategy

## Status

Accepted

## Context

mmap 消除堆拷贝，但每次冷读仍需解码 + 页访问。块级缓存可显著提升热点块的
随机读。候选淘汰策略：

- **LRU**：块级访问呈近期性（热点块短时间内重复访问）；
- **LFU**：全局频率，但块级命中模式不如 LRU 直觉；
- **TinyLFU**：频率 + 准入，内存效率最高，实现复杂；
- **ARC**：已实现键级策略，块级引入成本高。

## Decision

采用 **LRU Block Cache**：

```text
GET → BlockCache.get(CacheKey{tableId, blockOffset})
  hit  → Block.decode（缓存 DirectByteBuffer，零拷贝）
  miss → mmap read → MemoryPool.allocate → cache.put → decode
```

1. `CacheKey` = 表 id + 块偏移；`CacheEntry` = 池化 DirectByteBuffer；
2. 容量：条目数（默认 1024，可配置 0=禁用）；
3. `invalidate(tableId)`：表被 compaction 删除时失效；`clear()` 全清；
4. 淘汰：LRU 条目释放回 MemoryPool（off-heap 复用）；
5. `CacheStatistics`：hit / miss / eviction / hitRate。

## Alternatives

1. LFU：块级频率分布不均，收益低于复杂度；
2. TinyLFU：Phase 10 候选（届时新 ADR）；
3. ARC：键级已用，块级重复实现成本高。

## Consequences

**优点：** 热点块延迟接近内存；off-heap 缓存减少堆压力。
**缺点：** LRU 对扫描型访问命中率低（块级可接受）。
**风险：** 缓存与文件删除竞态 → invalidate(tableId) + 读时 CRC 校验。

## Implementation

- `io.tieringkv.cache.block`：BlockCache / CacheKey / CacheEntry /
  CachePolicy / CacheStatistics；
- ColdStorageEngine 默认启用（容量 1024）。
