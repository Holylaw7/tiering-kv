# ADR-0076: Transaction Recovery

## Status

Accepted

## Context

崩溃/leader 变更后遗留锁与 provisional state 会造成永久阻塞。

## Problem

需要把 ACTIVE/PREWRITING/PREPARED 收敛到 COMMITTED 或 ROLLED_BACK。

## Decision

- `TransactionRecoveryManager`：
  - 启动扫描 LockTable/事务记录/WAL；
  - primary 已提交 → 完成 commit（写 WriteRecord + 删锁）；
  - primary 未提交且 TTL 过期 → rollback（删 provisional + 释放锁）；
  - 无法判定 → ABORTED 终态（禁止 UNKNOWN 永久状态）；
- 事务状态持久化经 `TxnJournal`（Raft 记录或内存记录）。

## Alternatives

1. 恢复时全部回滚：已提交事务丢失，否决。
2. 人工干预：不可接受。
3. 状态机重放（Raft log 重放）：随 Raft 恢复自然完成，
  本阶段 LockTable 重建 + TTL 判定。

## Consistency Model

已提交事务永不丢失；未提交事务不虚假成功；无永久锁。

## Failure Model

任意阶段崩溃均可恢复。

## Recovery Model

扫描 → 判定 → 收敛；幂等。

## Performance Impact

仅启动与超时路径触发。

## Compatibility

不影响正常事务路径。

## Implementation

- `mvcc/TransactionRecoveryManager.java`
