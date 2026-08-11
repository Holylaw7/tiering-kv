# ADR-0084: Transaction Metadata Raft

## Status

Accepted

## Context

`PersistentTxnJournal` 绑定单个 Region 节点；Coordinator 崩溃后没有全局
“该事务涉及哪些 Region、当前处于什么阶段”的权威视图，恢复依赖各 participant
本地日志推断。

## Decision

新增 `TransactionMetadataService`，由专用 Raft 组 `txn_meta_region` 承载：

- 记录 `txnId / primary / participants / state / startTS / commitTS`；
- 状态机命令：REGISTER / PREPARE / COMMIT / ROLLBACK；
- Coordinator 崩溃重启：从 Raft 重放元数据，对 PREPARED/COMMITTED 继续
  补完，对 ROLLED_BACK 清理，禁止 UNKNOWN 终态；
- 元数据 Raft 组复用现有 RaftNode（Memory/File RaftLog），不修改共识语义。

## Alternatives

1. 元数据写业务 Region：与 MVCC 版本混存，污染存储语义。
2. 协调器内存状态：崩溃即丢失。
3. 仅靠本地日志：无全局视图，跨 Region 恢复不确定。

## Consequences

优点：

- 全局事务状态可恢复，Coordinator 崩溃可续跑；
- 与 Region 数据路径隔离。

缺点：

- 多一个 Raft 组（元数据路径成本低）；
- 元数据组本身需要多数派可用。

风险：

- 低；由 CoordinatorCrashRecoveryTest / MetadataLeaderFailoverTest 验证。

## Implementation

- `src/main/java/io/tieringkv/transaction/metadata/`：
  TransactionMetadataService、TransactionMetadataState、TxnMetaEntry；
- 测试：CoordinatorCrashRecoveryTest、MetadataLeaderFailoverTest。
