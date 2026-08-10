# ADR-0054: Async Proposal Pipeline

## Status

Accepted

## Context

Phase 14 的复制吞吐 37–68K ops/s，上限受同步等待写者（每次
`propose().get()`）限制；P99 尾延迟受 flush 周期影响。

## Problem

- 需要消除 `Future.get()` 阻塞等待；
- 需要批量 proposal（1000 请求 → 单次 AppendEntries）；
- 需要 leader 变更 fail-callback + 客户端自动重试；
- 需要有界队列背压（NORMAL/WARNING/CRITICAL）。

## Options

1. **同步等待（现状）**：吞吐受限；
2. **异步提案队列（选定）**：`AsyncProposalQueue` + `BatchCollector` +
   callback 完成；
3. **线程池阻塞**：资源浪费，否决。

## Decision

采用 **AsyncReplicationClient**：

```text
client → AsyncProposalQueue（有界，背压三态）
  → BatchCollector（合并条目 → raft.propose）
  → Raft 复制 → Callback（成功/失败）
AsyncProposalContext：requestId / term / deadline / callback
```

1. 队列满（WARNING）→ 拒绝新请求（非阻塞返回）；
2. CRITICAL → 等待信号量（限时）；
3. leader 变更：旧 leader 的 pending 全部 fail-callback，客户端按
   requestId 自动重试到新 leader；
4. 禁止在提案路径调用 `Future.get()`。

## Consequences

**优点：** 吞吐量级提升（目标单 shard >100K、64 写者 >200K）；
**缺点：** 回调式编程复杂度上升；
**风险：** 背压参数需校准。

## Implementation

- `io.tieringkv.cluster.raft.client`：AsyncProposalQueue /
  AsyncProposalContext / AsyncReplicationClient。
