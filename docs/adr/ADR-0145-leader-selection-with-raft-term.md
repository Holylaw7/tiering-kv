# ADR-0145: Leader Selection with Raft Term

## Status

Accepted

## Context

Phase 32 的 LeaderSelector 仅基于健康探测，缺少 Raft term/epoch 约束：
网络分区或旧纪元节点可能在低 term 下自封 leader，形成脑裂。

## Decision

1. `replication/active/RaftAwareLeaderSelector`：term 单调 + 健康探测 +
   自动选主；候选 term 低于当前已知 term 时拒绝自封；
2. 与 Raft 集群元数据联动：term 来源为 Raft 状态（term/epoch）；
3. 验收：term 回退拒绝、故障切换正确、仲裁兜底。

## Alternatives

1. 仅健康探测：无 term 约束，分区脑裂；
2. 完全依赖 Raft 选举：跨地域选主 RTO 高、与流量治理解耦难。

## Consequences

优点：防脑裂 + 自动切换，RTO 可控。

缺点：需要 term 来源接入（Raft 状态或心跳）。

风险：term 时钟漂移由单调递增约束兜底。

## Implementation

代码影响范围：`replication/active/RaftAwareLeaderSelector` + 测试 +
`docs/multi-region/raft-aware-leader.md`。
