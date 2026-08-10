# IO 优化详细设计（IO Design）

状态：✅ 已实现（Phase 8，ADR-0026 / 0027 / 0028）

## 1. 读路径

```text
GET → ColdStorageEngine → BlockCache（LRU）
  hit  → decode（DirectByteBuffer）
  miss → MmapSSTableReader（MappedByteBuffer 切片 + CRC）→ cache insert
FileChannelSSTableReader 保留为 baseline（benchmark 对比/降级）
```

## 2. mmap（ADR-0026）

- MappedFile：FileChannel.map(READ_ONLY) → MappedByteBuffer；
- 块读 = slice(offset, size) 零拷贝；CRC 校验不变；
- close：清引用，映射由 JDK 在 GC 时解除（限制见 §6）。

## 3. Off-Heap 池（ADR-0027）

- 大小类：4K / 16K / 64K / 256K；allocate/release/reuse；
- AllocationTracker：allocated/released/reuse/peak；
- BlockCache 缓存体为池化 DirectByteBuffer。

## 4. Block Cache（ADR-0028）

- LRU（accessOrder LinkedHashMap，容量 1024）；
- CacheKey = (tableId, blockOffset)；invalidate(tableId) 随 compaction；
- 淘汰条目回池。

## 5. 指标

IOStatistics：readCount / cacheHit / cacheMiss / mappedBytes / readLatency
（pageFault 在 JVM 不可直接观测，以冷读延迟代理）。

## 6. 已知限制

- MappedByteBuffer 解除映射依赖 GC（无 Unsafe）；文件数需控制；
- 池与缓存参数需 Phase 9 调优；
- 10M 键基准需手动运行（自动化套件含 100K/1M）。
