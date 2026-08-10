# ADR-0026: SSTable IO Strategy Selection

## Status

Accepted

## Context

SSTable 随机读当前走 FileChannel.read() → Heap byte[] → Decode：
每次读产生堆分配与拷贝，冷读路径 GC 压力大。候选：

- **FileChannel read**：现状 baseline，语义简单；
- **mmap**：MappedByteBuffer 映射文件，随机块读零用户态拷贝、由 OS page
  cache 管理缓存；
- **Async IO**：异步读回调，避免线程阻塞，但 JDK 17 无统一稳定 API，
  且随机读延迟主要由 page cache/磁盘决定；
- **io_uring**：Linux 最优异步方案，但 JDK 17 无稳定绑定（Java 21+ 才逐步
  支持），跨平台不可行。

## Decision

采用 **mmap 生产读取 + FileChannel baseline 保留**：

1. `MmapSSTableReader`：open → map（READ_ONLY）→ footer/index/bloom 校验 →
   块定位 → MappedByteBuffer 切片 → CRC 校验 → 零拷贝解码；
2. `FileChannelSSTableReader`（现状实现）保留，作为 benchmark baseline 与
   降级路径；
3. 两种路径共用 BlockIndex / BloomFilter / Block 格式（SSTable 格式不变）；
4. async IO / io_uring：Phase 10 评估（届时新 ADR）；
5. 内存影响：mmap 使用 off-heap 映射 + OS page cache，减少堆 byte[] 分配；
   页缺失（page fault）发生在冷页首次访问（JVM 无法直接观测，以冷读延迟代理）。

## Alternatives

1. 纯 FileChannel：无堆拷贝优化；
2. 纯 async IO：API 不统一、收益未验证；
3. io_uring：JDK17 无稳定绑定，否决。

## Consequences

**优点：** 冷读零拷贝、堆分配显著下降、与现有格式兼容。
**缺点：** 映射生命周期管理（close 依赖 GC 解除映射，见限制）。
**风险：** 大文件映射占用地址空间 → 按需映射 + 限制打开表数量。

## Implementation

- `io.tieringkv.storage.io`：MappedFile / FileRegion / MmapSSTableReader /
  FileChannelSSTableReader / BlockDecoder / IOStatistics；
- ColdStorageEngine 默认 mmap + BlockCache（ADR-0028）。
