# Multi-Region Replication 设计

Phase 27 · ADR-0108

## 1. 架构

```text
Region A（leader） → ReplicationPipeline → Region B/C（follower）
                        ├─ ReplicaSink（async/sync）
                        ├─ LagTracker（滞后观测）
                        └─ ConflictDetector（同 key 多来源标记）
```

复制载体复用 CDC 事件流（ADR-0105），不引入第二套日志。

## 2. 模式

- ASYNC：即投即确认，写路径零阻塞（存在窗口丢失风险）；
- SYNC：等待全部副本 ack（带超时），写路径受 RTT 影响。

## 3. 冲突

同 key 多来源写入由 ConflictDetector 标记（主地域优先）；REGION_MOVE
重置来源记录。双向复制/CRDT 为 Phase 28 方向。

## 4. 使用

```java
ReplicationPipeline pipeline = new ReplicationPipeline(
        List.of(replicaSink), ReplicationMode.SYNC, 2_000, "r1");
pipeline.replicate(changeEvent).join();
```
