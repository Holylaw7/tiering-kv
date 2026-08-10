# ADR-0081: Transaction Journal Raft Persistence

## Status

Accepted

## Context

Phase 19 的 `TxnJournal` 仅记录命令，协调器未在 prewrite/commit/rollback
各阶段落日志；节点/leader 崩溃后事务状态依赖锁超时推断，缺少
“已准备但未提交”的确定性恢复依据。

## Decision

所有事务状态进入 Raft：

- `PersistentTxnJournal`：按 `PREWRITE / COMMIT / ROLLBACK` 状态写入
  Raft 提案（propose 完成即视为持久化），并本地文件追加作为兜底；
- `TxnRecoveryReplay`：启动时重放日志，把事务恢复到
  COMMITTED / ROLLED_BACK / ABORTED 之一，禁止 UNKNOWN；
- `TransactionCoordinator` 增加可选 journal 注入：prewrite 前记
  PREWRITE，commit 前记 COMMIT，rollback 前记 ROLLBACK；
- leader crash 语义：
  - COMMIT 已持久化 → 必须补完提交（无丢失）；
  - 仅 PREWRITE → 超时/协调器决策回滚（无幻影提交）；
  - ROLLBACK 已持久化 → 必须清理锁与 provisional 状态。

## Alternatives

1. 仅靠锁 TTL 推断：无法区分已提交与悬挂，恢复不确定。
2. 协调器内存状态机：崩溃即丢失。
3. 状态写入业务存储：与 MVCC 版本混存，复杂。

## Consequences

优点：

- 恢复确定性，no phantom commit / no lost commit；
- 与 Phase 15 已修复的 failPendingFromLocked 语义一致。

缺点：

- 每条事务状态一次 Raft 提案，吞吐下降；

风险：

- 中；通过 TxnLeaderCrashTest / TxnReplayTest 验证。

## Implementation

- `src/main/java/io/tieringkv/mvcc/`：PersistentTxnJournal、
  TxnStateRecord、TxnRecoveryReplay；
- `TransactionCoordinator` journal 注入；
- 测试：TxnLeaderCrashTest / TxnReplayTest。
