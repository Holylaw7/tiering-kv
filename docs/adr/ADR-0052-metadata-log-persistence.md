# ADR-0052: Metadata Raft Log Persistence

## Status

Accepted

## Context

Phase 13 的 MetadataRaftGroup 状态机为进程内内存态（MemoryRaftLog），
元数据节点重启后拓扑/分片/slot 归属全部丢失。

## Problem

- 需要持久化节点注册表、slot 表、迁移状态；
- 需要崩溃恢复（snapshot + raft log replay）；
- 需要保留故障转移能力。

## Options

1. **内存态（现状）**：重启丢失；
2. **复用 FileRaftLog + Snapshot（选定）**：元数据命令落盘，状态机
   快照压缩日志；
3. **外部配置中心**：依赖外部系统。

## Decision

采用 **FileRaftLog + MetadataSnapshot + MetadataRecovery**：

```text
MetadataRaftGroup
  ├── FileRaftLog（命令持久化，SYNC/ASYNC）
  ├── RaftPersistentState（term/votedFor/commitIndex）
  └── MetadataSnapshot（状态机序列化：nodes/slotTable/migration）
恢复：加载快照 → 重放剩余日志 → 重新参与集群
```

1. `MetadataSnapshot` 序列化 MetadataState（含 slot 表与迁移状态）；
2. 日志超过阈值自动快照压缩；
3. 验收：杀 leader → 重启 → 拓扑保留。

## Consequences

**优点：** 元数据持久化、重启自愈、日志有界；
**缺点：** 增加一次磁盘写（低频命令，可接受）；
**风险：** 快照与日志一致性由 Raft 提交序保证。

## Implementation

- `io.tieringkv.cluster.metadata`：MetadataRaftLog（封装 FileRaftLog +
  SnapshotManager）、MetadataRecovery；
- MetadataRaftGroup 构造支持持久化目录。
