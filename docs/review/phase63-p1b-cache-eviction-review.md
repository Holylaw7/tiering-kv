# Phase 63 Review — P1b Cache & Eviction Optimization

## 总体结论

Optimization Roadmap P1b 完成：ARC byte 容量、Segment LFU + Async
Buffer、HotCache version check。全量回归 **14714 tests / 0 failures**
（本地），真实 Runner 门禁全绿。

## 交付清单

1. **ARC byte 容量**（ADR-0326，TD-005）：ARCPolicy(long) 字节模式
  （per-key size + usedBytes，按内存字节淘汰），entry 模式兼容；
2. **Segment LFU**（ADR-0327，TD-006）：SegmentLFUPolicy（onAccess
  无锁入队、16 段独立锁索引、drain 合并、候选全局最小）；
3. **HotCache version check**（ADR-0328，TD-018）：StorageEngine.
  versionOf（默认 0）+ MemTable 键版本 + 热缓存"版本一致即新鲜"
  （消除 TTL 陈旧窗口，无版本存储回退 TTL）。

## 测试与门禁

- 新增测试 12 项（ARC byte 4 + SegmentLFU 5 + HotCache version 3）；
- 全量回归 14714 / 0 failures / 6 skipped；
- 修复：ARC byte 模式 ghost 容量死循环（capacity=-1 下 entry 裁剪永真）。

## 已知限制（如实记录）

- SegmentLFU 索引有缓冲窗口滞后（candidate 前 drain 保证决策新鲜）；
- 无版本存储（versionOf=0）热缓存仍依赖 TTL 兜底；
- ARC byte 模式 ghost 不做字节裁剪（仅记录键，内存开销小）。

## 后续

- P1c（并发/性能）与 P1d（v4 模块增强）按 optimization-roadmap 推进。
