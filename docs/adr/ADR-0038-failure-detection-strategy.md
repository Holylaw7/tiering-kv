# ADR-0038: Failure Detection Strategy

## Status

Accepted

## Context

需要检测节点失败并触发选举/重路由。候选：固定超时、随机化选举超时、
租约。

## Decision

采用 **心跳 + 随机化选举超时**：

```text
Leader：heartbeat 周期（默认 25ms）广播 AppendEntries
Follower/Candidate：electionTimeout = base(100ms) + random(0..50ms)
  → 超时未收到心跳 → 转 Candidate → 自荐 + 请求投票
  → 多数派 → 新 Leader
```

1. 任期（term）单调，旧任期请求被拒绝；
2. 每任期每节点一票（votedFor）；
3. 失败后：元数据 LEADER-CHANGE → 客户端重路由；
4. Replica 崩溃：从节点组移除，剩余节点继续服务；
5. 目标：选举时间 <5s（实测进程内毫秒级）。

## Alternatives

1. 固定超时：多节点同时超时概率高；
2. 租约：复杂，Phase 11 不需要。

## Consequences

**优点：** 简单、可预测、防同时竞选。
**缺点：** 网络分区需额外处理（原型仅进程内）。
**风险：** 时钟漂移 → 以单调时钟 + 相对超时。

## Implementation

- `RaftNode` 选举定时器 + `LeaderElection`；
- 测试覆盖：leader 崩溃 → 新 leader、replica 崩溃 → 集群继续。
