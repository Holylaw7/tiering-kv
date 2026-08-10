# 并发优化详细设计（Concurrency Design）

状态：✅ 已实现（Phase 7，ADR-0023 / 0024 / 0025）

## 1. 执行模型（ADR-0023）

```text
Netty EventLoop → CommandEngine.executeAsync → KeyShardExecutor
    → ShardRouter（fnv1a % N）→ ShardQueue → ShardWorker → StorageEngine
    → ResponseSequencer（每连接按序号释放响应）
```

- 同键 FIFO；异键并行；响应保序；
- 用户线程不执行 flush/migration/compaction。

## 2. MemTable（ADR-0024）

- 256 段 Striped RWLock（64→256）；
- 全量读维持读锁；热点读走 HotKeyReadCache（无锁子集）；
- 未验证 lock-free 不引入（TD-015）。

## 3. 热点键（ADR-0025）

- AccessCounter 时间窗计数 → HotKeyDetector（阈值 1000/窗，上限 1024）；
- HotKeyReadCache：TTL 500ms + 写前/写后失效；
- RequestCoalescer：同键并发 GET 合并 single loader。

## 4. 指标

ConcurrencyMetrics：队列深度（当前/峰值）、分片利用率、等待时间、
操作延迟；与 StorageMetrics 互补。

## 5. 已知限制

- 热点缓存存在 ≤TTL 陈旧窗口（写失效兜底）；
- 分片倾斜由热点检测缓解，动态重分片留 Phase 10；
- 全量无锁读留 TD-015（验证后新 ADR）。
