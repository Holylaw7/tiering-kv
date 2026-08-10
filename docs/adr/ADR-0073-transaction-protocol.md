# ADR-0073: Transaction Protocol

## Status

Accepted

## Context

需要分布式事务协议；单 Region 与跨 Region 统一。

## Problem

无事务协议时无法保证多键原子性与隔离。

## Decision

- Percolator-style 2PC：
  - BEGIN：分配 startTS；
  - Prewrite：校验冲突 → 写 LockRecord + provisional mutation
    （对外不可见）；
  - Commit：校验 primary lock → 分配 commitTS（> startTS，单调）→
    写 WriteRecord → 删 LockRecord → 可见；
  - Rollback：释放锁 + 删 provisional state；
- 状态机：ACTIVE → PREWRITING → PREPARED → COMMITTED / ROLLED_BACK；
  ABORTED 为终态异常；
- `Transaction` API：begin/get/put/delete/commit/rollback；
- 跨 Region：`TransactionCoordinator` 2PC（全 participant prewrite
  成功才 commit；任一失败 rollback all）。

## Alternatives

1. 单阶段写 + 回滚日志：原子性窗口不可控，否决。
2. 悲观锁 2PL：死锁处理复杂，本阶段乐观优先。
3. 分布式一致性协议（Paxos 事务）：超出本阶段。

## Consistency Model

Snapshot Isolation（SI）：读已提交快照；写写冲突在 prewrite 拒绝。

## Failure Model

leader 变更/超时 → future 显式失败；旧 leader 不虚假成功。

## Recovery Model

见 ADR-0076：异常状态最终收敛 COMMITTED / ROLLED_BACK。

## Performance Impact

2PC 每事务 2 轮 Raft；冲突时 abort 重试。

## Compatibility

Redis GET/SET/DEL 自动包装为单键事务，协议层不变。

## Implementation

- `mvcc/Transaction.java`、`TransactionManager.java`、
  `TransactionCoordinator.java`、`PrewriteExecutor.java`、
  `CommitExecutor.java`、`RollbackExecutor.java`
