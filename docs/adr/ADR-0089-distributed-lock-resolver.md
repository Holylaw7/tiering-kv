# ADR-0089: Distributed Lock Resolver

## Status

Accepted

## Context

分布式事务中 orphan lock（协调器崩溃/网络超时遗留的锁）会永久阻塞 key；
需要 TiKV 风格解析：发现锁 → 检查 primary → 决定 commit 或 rollback。

## Decision

- `LockResolver`：对目标 key 的锁：
  1. DetectLock：从 participant LockTable 发现锁；
  2. CheckPrimary：查询 primary 锁/元数据决定事务状态；
  3. ResolveCommit：primary 已提交/决策已持久化 → 补完 commit；
     ResolveRollback：无提交决策且超时 → 回滚并释放锁；
- `TxnStatusCache`：txnId → 状态缓存（带 TTL），避免重复解析；
- 覆盖场景：orphan lock、coordinator crash、network timeout；
- 解析动作全部复用幂等 participant RPC（COMMIT/ROLLBACK/HEARTBEAT）。

## Alternatives

1. 直接按锁 TTL 回滚：可能误杀已提交事务。
2. 人工清理：不可扩展。
3. 全局锁服务：引入新组件，复杂。

## Consequences

优点：

- 无永久锁，且不误杀已提交事务；
- 状态缓存降低解析开销。

缺点：

- 解析期间可能阻塞写者（可接受，窗口短）。

风险：

- 低；由 PrimaryCrashResolveTest / SecondaryLockCleanupTest 验证。

## Implementation

- `transaction/lock`：LockResolver、TxnStatusCache；
- 复用 TransactionParticipant 幂等 RPC。
