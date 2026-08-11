# ADR-0099: Metadata Multi-Raft Network Transport

## Status

Accepted

## Context

Phase 24 完成事务元数据 Multi-Raft 架构（TxnMetadataNode + 快照 +
decisionIndex，ADR-0095），但三节点元数据组仍运行在进程内
（LocalRaftTransport）。事务决策链路（Coordinator → 元数据组 → 决策
apply）是控制面最后一个未网络化的闭环（TD-050）。Region 组已通过
MultiRaftEndpoint 单端口共享传输（ADR-0058）完成网络化，元数据组应复用
同一传输框架，避免引入第二套 RPC。

## Decision

事务元数据 Raft 组接入 MultiRaftEndpoint 共享传输：

1. `TxnMetadataNode` 生产构造：MultiRaftTransport(groupId, endpoint) +
   FileRaftLog(SYNC) + RaftPersistentState + SnapshotManager，节点重启
   保留 term/votedFor/日志/快照；
2. MultiRaftEndpoint 新增组提案 RPC（META_PROPOSE / META_STATUS），
   提案仍由 leader 本地 propose → 复制 → commit → apply，Raft-first +
   decisionIndex 语义不变（ADR-0087/0095）；
3. `TxnMetadataClient` 增加网络模式：按节点列表轮询，leader 接受提案，
   非 leader 返回重定向错误，客户端重试下一节点；
4. 元数据命令继续复用 TxnMetaCommand（REGISTER/PREPARE/COMMIT/ROLLBACK/
   LIFECYCLE），仅传输层升级。

## Alternatives

1. 为元数据组另建独立 RPC 服务：端口/编解码/连接池重复实现，与
   ADR-0058 单端口多组设计冲突；
2. 将提案也复制到全部节点再本地 apply（客户端多写）：决策一致性依赖
   客户端，破坏 Raft 单一 leader 语义；
3. 保持进程内传输：TD-050 不关闭，跨机决策链路无法验证。

## Consequences

优点：元数据组与 Region 组共用单端口传输与组隔离；决策链路获得真实网络
语义（failover/重试/分区）；可跨机部署。

缺点：新增两类 RPC 消息；客户端需处理 leader 重定向。

风险：提案 RPC 重试可能重复写入（元数据命令按 txnId 幂等，状态机层
安全）；跨机时钟与网络延迟影响 failover 时长，需基准记录。

## Implementation

代码影响范围：

- `cluster/rpc/RpcMessageType`（新增 META_PROPOSE/META_STATUS 及响应）；
- `cluster/rpc/MultiRaftEndpoint`（组提案/状态处理）；
- `txn/meta/TxnMetadataNode`（网络 + 持久化构造）；
- `txn/meta/TxnMetadataClient`（网络模式）；
- `txn/meta/MetadataSnapshotManager`（字节序列化供快照状态机复用）。
