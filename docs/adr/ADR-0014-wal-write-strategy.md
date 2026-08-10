# ADR-0014: WAL Write Strategy Selection

## Status

Accepted

## Context

一致性模型要求"WAL 持久化成功后才能返回成功"。候选策略：

- Sync WAL：每次 write + fsync + ack —— 最安全，但单次 fsync 成本（SSD 约
  0.1–1ms）直接进入请求延迟，P99 < 1ms 目标难以满足；
- Async WAL：仅写 OS 缓冲、后台 flush —— 吞吐最高，但进程/内核崩溃丢数据；
- Group Commit：批量收集写入、一次 fsync 覆盖一批 —— 兼顾吞吐与持久性。

## Decision

采用 **可配置 fsync 策略 + 近似 Group Commit**：

```text
WALConfig.FsyncPolicy { ALWAYS, EVERY_SEC, NO }
```

1. **默认 EVERY_SEC**：append 写入当前 segment 缓冲；距离上次 force 满 1s 时
   批量 flush + force（一次 fsync 覆盖 ≤1s 的全部写入，即 group commit 语义）；
   返回成功 = 已写入 OS（最坏丢失窗口 ≤1s，Redis everysec 同款）；
2. **ALWAYS**：每次 append 后立即 flush + force，严格持久化（配置可选）；
3. **NO**：不 force，仅用于测试/性能对比；
4. **segment 轮转**：切换 segment 前必须 force 旧 segment；
5. 失败语义：append 抛 `WalWriteException`，写路径不进入 MemTable，
   命令层返回错误（不谎报成功）。

## Alternatives

1. 纯 Sync：正确但延迟不可接受，作为 ALWAYS 保留给强一致场景；
2. 纯 Async：吞吐最高但违反"持久化成功才能返回成功"；
3. 独立后台 flush 线程 + 队列：更精细的 group commit，但复杂度高，
   Phase 7 并发优化时评估。

## Consequences

**优点：** 默认策略满足 P99 < 1ms 目标且提供 ≤1s 丢失窗口；ALWAYS 提供强一致
选项。
**缺点：** EVERY_SEC 有 1s 丢失窗口；NO 仅限测试。
**风险：** fsync 抖动 → EVERY_SEC 在 force 点产生尖峰，Phase 9 用 metrics 观测。

## Implementation

- `io.tieringkv.storage.wal`：WALConfig、WALWriter（策略执行）、WALManager；
- 默认 `EVERY_SEC`；Main 与 Benchmark 均采用默认。
