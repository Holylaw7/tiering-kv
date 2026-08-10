# ADR-0050: Adaptive Raft Replication

## Status

Accepted

## Context

Phase 13 的批量复制使用固定 `maxBatchEntries/flushInterval/maxInflight`。
低负载下固定大 batch 增加延迟，高负载下固定小 batch 限制吞吐。

## Problem

- 需要根据 pending 条数、网络延迟、follower 滞后动态调整；
- 需要异步客户端提案（timeout / cancellation / retry）；
- 目标：吞吐 22K → >50K ops/s，同时保持 Raft 安全。

## Options

1. **固定配置（现状）**：简单，无法两全；
2. **自适应控制器（选定）**：`ReplicationController` 按负载输出
   batch size / flush interval；
3. **纯异步批量客户端**：与控制器叠加（选定）。

## Decision

采用 **ReplicationController + 异步客户端提案**：

```text
输入：pending 条数 / 网络 RTT（滑动均值） / follower 滞后
输出：batchSize（低延迟 16 → 高吞吐 512）、flushInterval（1ms → 10ms）
```

1. 高 pending / 低 RTT → 增大 batch、缩短 flush；
2. follower 滞后大 → 缩短 flush 加速追赶；
3. `ReplicatedStorageEngine.putAsync` 返回 CompletableFuture，
   支持超时（默认 5s）、取消（future.cancel）、leader 变更重试；
4. 同步 put 保留为兼容层（内部 await putAsync）。

## Consequences

**优点：** 吞吐/延迟随负载自适应，客户端不阻塞；
**缺点：** 控制器参数需基准校准；
**风险：** 动态参数变化不破坏 Raft 安全（仅影响发送节奏）。

## Implementation

- `io.tieringkv.cluster.raft`：ReplicationController；
- RaftNode 使用控制器输出替换固定配置；
- ReplicatedStorageEngine：putAsync / putAsyncBatch。
