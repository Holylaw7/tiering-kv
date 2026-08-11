# ADR-0108: Multi-Region Replication

## Status

Accepted

## Context

Phase 26 完成 CDC exactly-once 事件流（ADR-0105）。跨地域复制需要
把已提交变更可靠地投递到远端地域，并观测滞后与冲突。若另建一套复制
日志会与 CDC 重复；复用 CDC 事件作为复制载体最经济。

## Decision

新增 `replication/`：

1. `ReplicationPipeline`：以 ChangeEvent 为复制单元，向各地域
   `ReplicaSink` 投递；ASYNC 模式即投即确认，SYNC 模式等待全部
   replica ack（带超时）；
2. `ReplicaState` + `LagTracker`：按 replica 记录已应用 seq 与滞后；
3. `ConflictDetector`：同 key 多来源写入标记冲突（主地域优先）；
4. 单地域路径零回退：pipeline 仅旁路附加，不进入 Raft/MVCC 主链。

## Alternatives

1. 双写 Raft 日志：复制与共识耦合，违反 additive 原则；
2. 独立 binlog 格式：与 CDC 重复维护。

## Consequences

优点：复制与 CDC 同源，故障语义一致；滞后可观测。

缺点：ASYNC 模式存在窗口内丢失风险（可配置 SYNC 缓解）。

风险：SYNC 模式受远端 RTT 影响写路径，需超时与降级策略。

## Implementation

代码影响范围：`replication/`（Pipeline/ReplicaState/LagTracker/
ConflictDetector）+ 测试 + `docs/multi-region/replication-design.md`。
