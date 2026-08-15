# ADR-0333: Replication Pipeline Enhancement

## Status

Accepted

## Context

M3 限制：跨集群复制为同步 ack 单事件（5748 ops/s），水位显式刷盘，
冲突策略仅 LWW。目标：批量/异步 ack、水位周期刷盘、冲突策略抽象。

## Decision

- **批量发送**：`CrossClusterReplicationChannel.sendBatch(List<
  ChangeEvent>)`——一次 REPLICATION RPC 携带多事件（长度前缀批量
  编码），远端批量应用；单事件 send 保留（兼容）；
- **异步 ack**：提供 `sendAsync`（不等待响应，metrics 记录成功/
  失败计数）；SYNC 路径保留；
- **水位周期刷盘**：CrossClusterWatermark 增加周期 checkpoint
  （定时刷盘，close 仍兜底）；
- **冲突策略抽象**：`ConflictResolver` 接口（accept(ChangeEvent,
  originCluster)），LwwConflictResolver 实现；CRDT 演进后续以新实现
  接入，不改调用方。

## Alternatives

1. 保持单事件同步：吞吐受限；
2. 直接 CRDT：复杂度高，M3 语义未稳定。

## Consequences

优点：吞吐提升（批量）、水位持久化及时、策略可插拔。

缺点：批量失败语义（部分成功）——逐事件水位保证可恢复。

风险：异步 ack 下错误检测滞后（metrics 补偿）。

## Implementation

`replication/cross/CrossClusterReplicationChannel.java`（+batch/async）、
`CrossClusterWatermark.java`（周期刷盘）、`ConflictResolver.java` +
`LwwConflictResolver` 实现 + 测试。
