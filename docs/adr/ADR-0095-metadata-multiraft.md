# ADR-0095: Transaction Metadata Multi-Raft Architecture

## Status

Accepted

## Context

TD-047：事务决策仅由单节点 `TransactionMetadataService` 承载，元数据组
`TxnMetadataRaftGroup` 未接入协调器；决策可用性等于单点可用性。

## Decision

- `TxnMetadataNode`：单节点 = RaftNode + 状态机（apply TxnMetaCommand），
  通过 MultiRaftEndpoint/RaftTransport 组网；
- `TxnMetadataRaftGroup`：3 节点元数据组，leader 提案，majority 提交后
  apply 状态（Raft-first，禁止 local-first）；
- `TxnMetadataClient`：协调器侧 proposer，把命令编码为 TXN_METADATA RPC
  提交到当前 leader，响应携带 commitIndex；
- `MetadataSnapshotManager`：按 Raft 快照阈值生成元数据快照，加速恢复。

## Alternatives

1. 单节点元数据：可用性/可靠性不足。
2. 元数据写入业务 Region：与 MVCC 版本混存。

## Consequences

优点：决策高可用、恢复确定。缺点：元数据组需要 3 节点。

风险：低；由 MetadataMultiRaftTest 验证。

## Implementation

- `txn/meta/`：TxnMetadataNode、TxnMetadataClient、MetadataSnapshotManager；
- 测试：MetadataMultiRaftTest。
