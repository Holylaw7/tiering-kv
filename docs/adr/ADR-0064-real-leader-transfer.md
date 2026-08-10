# ADR-0064: Real Leader Transfer

## Status

Accepted

## Context

Phase 16 的 leader 转移仅更新元数据 epoch，不触发真实 Raft 领导权交接。
生产系统需要：`TransferLeadership(target)` → 目标立刻选举 → 新 leader，
无数据丢失、term 正确、pending proposal 正确失败、client 自动重试。

## Decision

- `RaftNode.transferLeadership(String target)`：
  - 校验：LEADER、target ∈ peers、target.matchIndex >= lastLogIndex
    （日志追平，避免新 leader 缺日志）；
  - 发送 TimeoutNow（term = currentTerm+1 语义，遵循 Raft 论文）；
- 新增 `RaftTransport.timeoutNow(target, request)` 默认方法
  （默认 failedFuture，API 兼容），实现于 LocalRaftTransport /
  NettyRaftTransport / MultiRaftTransport；
- `RaftNode.receiveTimeoutNow(term, leaderId)`：follower 校验 term >=
  currentTerm 后立即触发选举（不等待选举超时）；
- pending proposal：旧 leader 交接后未提交条目由冲突截断显式失败
  （沿用 failPendingFromLocked，ADR-0054 语义）；
- client：AsyncReplicationClient / ReplicatedStorageEngine 通过
  leaderSupplier 自动指向新 leader 重试。

## Alternatives

1. 元数据级转移（Phase 16）：不产生真实交接，否决。
2. 先停 leader 再等超时选举：转移延迟 100-180ms+，否决。
3. 修改选举协议（pre-vote）：超出本阶段范围，后续评估。

## Consequences

优点：毫秒级真实交接；日志追平校验保证新 leader 无缺口；
协议消息新增为 additive（默认方法 + 新 RPC 类型）。

缺点：需为三类传输实现 timeoutNow；TimeoutsNow 语义需与选举超时
协同（立即选举可能与其他 candidate 竞争，由 Raft 随机化保证收敛）。

风险：目标节点离线时 transfer 失败返回（调用方重试/回退）。

## Implementation

- `cluster/raft/RaftNode`、`RaftTransport`、`cluster/rpc/*`
- `cluster/lifecycle/LeaderTransferManager.java`
- 测试：LeaderTransferTest（≥15）。
