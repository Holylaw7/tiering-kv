# 事务架构（Phase 19）

## 协议

Percolator-style 2PC：

- BEGIN：分配 startTS；
- Prewrite：冲突检查（锁/写写/读写）→ LockRecord + provisional(LOCK)；
- Commit：校验 primary 锁 → 分配 commitTS（> startTS，单调）→
  写 WriteRecord → 删 provisional → 释放锁；
- Rollback：删 provisional + 释放锁。

## 状态机

ACTIVE → PREWRITING → PREPARED → COMMITTED / ROLLED_BACK / ABORTED。

## 跨 Region

`TransactionCoordinator`：参与者声明键归属（Predicate），
全 participant prewrite 成功才 commit；任一失败 rollback all
（含失败 participant 的已部分 prewrite 键）。

## Raft 集成

`TxnJournal.Raft`：事务记录经 RaftNode.propose 持久化；leader 关闭/
变更 → journal future 显式失败（不虚假成功）。

## 恢复

`TransactionRecoveryManager`：超时锁回滚；primary 已提交则清理锁；
异常状态收敛 COMMITTED / ROLLED_BACK，无永久锁。
