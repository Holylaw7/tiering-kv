# ADR-0042: Replication Lag Optimization

## Status

Accepted

## Context

Phase 11 复制滞后实测 13–35ms：leader 在 `propose` 中携带的
`leaderCommit` 是发送前快照，提交发生后才推进的 commitIndex 要等下一
次心跳（25ms 周期）才下发到 follower。

## Problem

- 提交后 commitIndex 传播延迟 = 一个心跳周期，导致 follower 应用滞后；
- 目标：把复制滞后从 13–35ms 降到 <5ms。

## Options

1. **缩短心跳周期**：简单但增加网络/CPU 开销，且只是概率性改善；
2. **提交后立即补发 commitIndex**（选定）：零额外周期成本，延迟只受
   一次 RPC 往返约束；
3. **propose 响应后同步等待全部副本应用**：把复制延迟转移到写路径，
   牺牲写延迟，被否决。

## Decision

采用 **CommitNotifier 立即传播**：

```text
leader commitIndex 推进
  → CommitNotifier（锁外）
  → 立即发送 heartbeat（携带最新 leaderCommit）
  → follower 应用并返回
```

1. `FollowerProgress` 记录每个 follower 的 nextIndex / matchIndex /
   lastAckNanos，由 `ReplicationTracker` 统一维护；
2. `CommitNotifier` 在 `maybeCommitLocked` 提交成功后调度一次即时
   补发（去重：同一 commitIndex 只补发一次；若已有普通复制在途则合并）；
3. 目标：复制滞后 <5ms（进程内/TCP 单机房）；基准在
   `distributed-production-report.md` 中给出对比。

## Consequences

**优点：** 滞后从心跳周期约束变为 RPC 往返约束，量级显著下降；
**缺点：** 提交路径多一次小消息发送，可忽略；高提交频率下由去重合并
控制消息数；
**风险：** 网络抖动仍可能造成偶发滞后 → 心跳兜底保证最终一致。

## Future Evolution

- 批量提交通知（group commit）；
- 自适应通知窗口（延迟敏感度配置）。
