# ADR-0020: Tier Scheduling Model Selection

## Status

Accepted

## Context

自动冷热调度需要执行三类后台工作：Flush（MemTable → SSTable）、Migration
（内存 → 冷层）、Compaction。候选模型：

- **Synchronous Scheduling**：客户端线程直接执行磁盘工作——简单，但违反
  "用户请求线程不得执行 flush/migration/compaction"，延迟不可控；
- **Asynchronous Worker Model**：有界队列 + 后台工作线程——客户端只入队，
  磁盘工作在后台执行，配合背压控制队列；
- **Event Driven Model**：单事件循环状态机——无多线程竞争，但串行执行、
  状态机复杂度高，无法并行 flush/migration/compaction。

## Decision

采用 **Asynchronous Worker Model**：

```text
Netty EventLoop → TieringController → TierWorkerPool（daemon）
    ├── Flush Worker      （FlushScheduler）
    ├── Migration Workers （MigrationScheduler，默认 2）
    └── Compaction Worker （CompactionManager 预留）
```

1. 客户端线程只做：背压检查 → WAL append → MemTable 写 → 事件上报；
2. 后台 worker 执行磁盘 IO；worker 异常被包装捕获，**不会导致 server 退出**；
3. 队列有界 + 背压（ADR-0021）：CRITICAL 时有界等待，超时拒绝写入；
4. Netty 事件循环永不阻塞在磁盘 IO 上。

## Alternatives

1. Synchronous：简单但违反线程模型约束，被否决；
2. Event Driven：串行吞吐受限，且与既有 Netty 事件循环职责冲突；
3. 每任务开线程：无界线程 + 调度开销，被否决。

## Consequences

**优点：** 客户端延迟稳定；flush/migration 可并行；异常隔离。
**缺点：** 多线程状态同步（去重、幂等）与背压调参成本。
**风险：** 队列堆积 → 背压兜底；worker 崩溃 → 包装重试/FAILED 保留数据。

## Implementation

- `io.tieringkv.storage.tiering`：TierWorkerPool、FlushScheduler、
  MigrationScheduler、TieringController；
- Main 组装；Phase 7 并发优化时复用该模型。
