# ADR-0037: Replication Model

## Status

Accepted

## Context

数据分片需要复制保证故障可恢复。候选：

- **Async Replication**：低延迟，但 leader 崩溃丢已确认写入；
- **Semi-Sync**：部分同步，实现复杂；
- **Raft Replication**：日志复制 + 多数派确认，强一致写入（推荐）。

## Decision

采用 **Raft Replication**：

```text
Client Write → Leader：append 本地日志
  → 并行 AppendEntries 到 follower → 多数派 ack
  → commitIndex 推进 → apply 到 TieringStorageEngine → 应答客户端
```

1. 日志顺序复制，条目携带 term/index；
2. follower 校验 prevLog（term/index），不一致则拒绝 → leader nextIndex 回退；
3. commit 仅当条目被多数派节点复制；
4. `ReplicatedStorageEngine` 为适配器：写入走 Raft，读取本地；
5. 存储核心（MemTable/WAL/SSTable）不改。

## Alternatives

1. Async：丢已确认写入，被否决；
2. Semi-Sync：多数派语义由 Raft 天然覆盖。

## Consequences

**优点：** 强一致写、故障自动恢复、与元数据服务模型统一。
**缺点：** 写延迟 = 多数派往返（原型进程内近似）。
**风险：** 日志分歧 → prevLog 校验 + term 比较解决。

## Implementation

- `io.tieringkv.cluster.raft`：LogEntry / AppendEntries / RaftNode /
  ReplicationManager / LeaderElection；
- `io.tieringkv.cluster.ReplicatedStorageEngine`。
