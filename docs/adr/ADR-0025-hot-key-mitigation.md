# ADR-0025: Hot Key Mitigation Strategy

## Status

Accepted

## Context

热点键（例如 90% 流量落在 1 个 key）会打满单个分片/段：读重复访问存储、
队列倾斜、锁竞争。候选缓解：

- 热点检测 + 本地读缓存；
- 同键请求合并（single loader）；
- 写热点限流/背压。

## Decision

采用**检测 + 本地读缓存 + 请求合并**组合：

```text
GET → HotKeyDetector（时间窗计数）
    ├── 热点：HotKeyReadCache（不可变缓存，TTL 默认 500ms）
    │       └── 未命中：RequestCoalescer（同键并发合并为 single loader）
    └── 非热点：StorageEngine 直读
PUT/DELETE → 写前 + 写后 invalidate（缓存一致性）
```

1. `AccessCounter`：按 `now / windowMillis` 分窗计数，跨窗重置；
2. `HotKeyDetector`：频率 ≥ 阈值（默认 1000 次/窗）→ 标记热点；写失效移除；
3. `HotKeyReadCache`：缓存 value + 过期时间；TTL 短（500ms），写失效保证
   版本一致（陈旧窗口 ≤ TTL，文档明示）；
4. `RequestCoalescer`：同键并发 GET 共享一次存储读取（10000 请求 → 1 次读）；
5. 写热点：依赖 KeyShardExecutor 同键串行 + TieringController 背压
   （不额外限流，避免误伤）。

## Alternatives

1. 仅限流：降低吞吐，不提升读效率；
2. 无缓存直接读：热点读重复穿透；
3. 永久缓存：版本一致性风险，被否决。

## Consequences

**优点：** 热点读无锁 + 合并；写失效保持正确性；实现独立可测。
**缺点：** 短 TTL 下的陈旧窗口；热点集合需裁剪（上限 1024）。
**风险：** 缓存与写入竞态 → 写前+写后双重失效 + 短 TTL 兜底。

## Implementation

- `io.tieringkv.concurrency.hotkey`：AccessCounter / HotKeyEntry /
  HotKeyPolicy / HotKeyDetector / HotKeyReadCache；
- `io.tieringkv.concurrency.RequestCoalescer`；
- `HotKeyStorageEngine` 装饰器；Main 组装。
