# ADR-0083: Distributed Transaction Protocol

## Status

Accepted

## Context

Phase 19/20 的 `TransactionCoordinator` 是进程内内存调用：Gateway 只能协调
本节点上的 Region participant。跨节点事务需要网络化 2PC：Coordinator 通过
RPC 对每个 Region 独立执行 PREWRITE / COMMIT / ROLLBACK / HEARTBEAT，
并保证 leader 崩溃时 no lost commit / no phantom commit / no permanent lock。

## Decision

新增 `io.tieringkv.transaction` 包：

- `DistributedTxnRouter`：Begin → Timestamp → 按 key 归属解析 Region →
  Prewrite RPC（全部成功）→ Commit RPC（带 commitTS）→ Ack；
  prewrite 失败回滚全部，COMMIT 决策持久化后不允许回滚；
- `RegionTxnClient`：单 Region 客户端（regionId + participant 节点 +
  重试）；`TxnParticipantClient`：单 participant 的 RPC 客户端；
- `TransactionParticipant`：每 Region 独立 participant，状态机
  LOCKED → PREPARED → COMMITTED / ROLLED_BACK；所有 RPC 幂等；
- 协议复用现有 MultiRaftEndpoint 单端口 + groupId 信封，新增
  `RpcMessageType.TXN_*` 消息与 `TxnRpcCodec`；
- 传输接口 `TxnTransport`：RPC 实现（真实 TCP）+ 本地实现（测试直调）。

## Alternatives

1. 网关内嵌全部 Region 存储：破坏多节点部署。
2. 自建独立事务端口：与 Raft RPC 分叉，运维/安全重复。
3. 复用现有 Coordinator 加序列化：未解决“跨节点”语义。

## Consequences

优点：

- 单 Region / 多 Region / 多节点统一走同一协议；
- 幂等 RPC 支撑重试与恢复。

缺点：

- participant 状态机 + 幂等实现复杂度上升；
- 每阶段一次 RPC 往返，延迟增加。

风险：

- 中；由 TxnNetworkFailureTest / CrossNodeTransactionTest 验证。

## Implementation

- `src/main/java/io/tieringkv/transaction/`：router / participant / rpc；
- `cluster/rpc`：RpcMessageType 扩展、TxnRpcCodec、MultiRaftEndpoint 分发；
- 测试：CrossNodeTransactionTest（TCP 三节点）、TxnNetworkFailureTest。
