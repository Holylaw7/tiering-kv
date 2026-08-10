# 自动调度详细设计（Tiering Design）

状态：✅ 已实现（Phase 6，ADR-0020 / 0021 / 0022）

## 1. 架构

```text
Command → TieringStorageEngine（背压 + 水位）
    → TieringController
        ├── WatermarkManager + BackPressureController
        ├── FlushScheduler → TierWorkerPool → FlushManager → SSTable
        ├── MigrationScheduler → MigrationLog → Worker → ColdStorage
        └── StorageMetrics
    → WALStorageEngine → MemTable
```

## 2. 调度模型（ADR-0020）

- TierWorkerPool：daemon 固定线程（flush 1 + migration 2，compaction 预留）；
- worker 异常包装捕获，不导致 server 退出；
- Netty 事件循环只做背压检查与入队。

## 3. 水位（ADR-0021）

| 级别 | 条件 | 行为 |
| --- | --- | --- |
| NORMAL | used < 85% | 正常写 |
| WARNING | used ≥ 85% 或 entryCount ≥ 1M | 异步 Flush，写不阻塞 |
| CRITICAL | used ≥ 95% 或队列 ≥ 10K | awaitWritable(超时) 后拒绝 |

## 4. FlushScheduler

- 触发：写后水位检查 / entryCount / 手动；
- 后台执行 FlushManager.flush（快照 → SSTable → 版本守卫 → WAL checkpoint）；
- flushInProgress 去重；失败保留内存，下次触发重试。

## 5. MigrationScheduler

```text
Eviction → MigrationTask（inFlight 去重）→ Worker
    → cold.put → WAL DELETE → removePhysicalIfVersion → SUCCESS
失败：重试上限（默认 3）→ FAILED（内存保留）
```

- MigrationLog 持久化状态（ADR-0022）；启动恢复未完成任务，幂等重放；
- 完成后压缩日志（仅保留未完成）。

## 6. BackPressure

- CRITICAL 时写路径有界等待（默认 1s），超时抛 BackpressureException →
  -ERR；队列/内存释放后唤醒。

## 7. Metrics

内存（used/max/entryCount）、迁移（pending/success/failed/latency）、
Flush（count/bytes/latency）、冷层（sstableCount/diskUsage）。

## 8. 已知限制

- 迁移异步化后，内存释放依赖 worker 吞吐；高峰期依赖背压兜底；
- 快照式 Flush（非真 Immutable MemTable 交换），Phase 7 评估；
- MigrationLog 尚未做定期压缩（仅启动时压缩）。
