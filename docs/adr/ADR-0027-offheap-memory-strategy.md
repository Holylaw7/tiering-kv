# ADR-0027: Off-Heap Memory Strategy

## Status

Accepted

## Context

解码与缓存路径产生大量 Heap byte[]，GC 压力高。候选：

- **Heap byte[]**：现状，分配快但 GC 压力大；
- **DirectByteBuffer**：off-heap、零拷贝友好，但分配/释放昂贵 → 需池化；
- **MemorySegment（JDK 17 孵化）**：API 更安全，但 JDK17 仍在孵化期，
  且与现有 ByteBuffer 生态需桥接；
- **Arena Allocation**：批量内存区域 + 指针分配，C 风格，实现复杂。

## Decision

采用 **DirectByteBuffer 大小类池**：

```text
MemoryPool → DirectBufferPool（4K/16K/64K/256K 槽位）
    → BufferArena（按需分配 + 显式回收）
    → BufferRecycler（借用/归还包装）
    → AllocationTracker（allocated/released/reuse/peak）
```

1. `allocate(size)`：按大小类复用或新分配 DirectByteBuffer；
2. `release(buffer)`：归还对应槽位（池有界），池满则丢弃（GC 回收）；
3. `reuse()`：命中池即复用（AllocationTracker.reuseCount++）；
4. 生命周期：显式 release；池 close 后清引用，native 内存由 JDK Cleaner
   随 GC 回收（不依赖 Unsafe）；
5. MemorySegment 留到 JDK 21 工具链升级时评估（届时新 ADR）。

## Alternatives

1. 纯 Heap byte[]：GC 压力未解决；
2. MemorySegment：JDK17 孵化期风险；
3. Unsafe 手动回收：任务禁止不稳定黑科技。

## Consequences

**优点：** 复用降低分配/释放成本；off-heap 支持零拷贝路径；统计完整。
**缺点：** 直接内存不可直接观测于常规 heap dump；池参数需调优。
**风险：** 池泄漏 → AllocationTracker 峰值监控。

## Implementation

- `io.tieringkv.memory`：MemoryPool / BufferArena / DirectBufferPool /
  BufferRecycler / AllocationTracker；
- BlockCache 缓存条目使用池化 DirectByteBuffer（ADR-0028）。
