# ADR-0044: Raft Batch Replication

## Status

Accepted

## Context

Phase 12 的 leader 采用同步串行 propose：append 后逐 follower 发送并
阻塞等待响应，实测 TCP 提交仅 700–1,359 ops/s。瓶颈是每请求 2 次串行
RPC 往返，而非日志/状态写入。

## Problem

- 需要降低每请求的 RPC 往返数量（批量）；
- 需要允许多个 AppendEntries 同时 in-flight（流水线）；
- 需要多个 propose 合并为一次提交（group commit）；
- 不能破坏 Raft 安全性：日志顺序、prevLog 校验、commit 多数派。

## Options

1. **串行复制（现状）**：简单，但吞吐受 RTT 限制；
2. **Batch AppendEntries（选定基础）**：多条日志合并为一个 RPC，
   可配置 `maxBatchEntries / maxBatchBytes / flushInterval`；
3. **Pipeline Replication（选定叠加）**：同 follower 允许
   `maxInflight` 个未确认请求，不等前一个响应；TCP 按连接保序，
   响应按序处理，nextIndex 回退语义安全。

## Decision

采用 **Batch + Pipeline 复制**：

```text
Leader propose → append log → pending（脏标记）
  → flush 条件：batch 满 / 字节满 / flushInterval 到
  → AppendEntries（携带 nextIndex..lastIndex 的批量条目）
  → 异步发送（同 follower 最多 maxInflight 未确认）
  → 响应按序处理：success → matchIndex/nextIndex 推进
  → 失败 → nextIndex 回退 → maybeCommit → CommitNotifier
```

1. 配置默认：`maxBatchEntries=128`、`maxBatchBytes=1MB`、
   `flushInterval=5ms`、`maxInflight=8`（可配置化）；
2. 批量构建按 nextIndex 从日志读取，天然覆盖 flush 间隔内全部新条目
   （group commit）；
3. 响应按 TCP 连接顺序到达，`ReplicationTracker` 增加 `inflight` 计数；
4. propose 返回 future，提交在异步响应路径完成；失败/超时回退
   nextIndex 并重发。

## Consequences

**优点：** RPC 数量下降一个数量级，吞吐目标 >5000 ops/s；延迟受
flushInterval 约束（默认 ≤5ms + 一次 RTT）；
**缺点：** 复制路径异步化，内存中 in-flight 请求有界（maxInflight ×
maxBatchBytes）；实现复杂度上升；
**风险：** 乱序/超时响应 → 按序处理 + nextIndex 回退兜底；旧测试中
"propose 返回前 follower 已 apply"的同步语义不再成立，测试改为等待
apply（语义随架构演进，非弱化断言）。

## Future Evolution

- 自适应 batch（按吞吐动态调整）；
- 与 WAL group commit 合并刷新；
- 优先级心跳（提交通知不排 batch 队尾）。
