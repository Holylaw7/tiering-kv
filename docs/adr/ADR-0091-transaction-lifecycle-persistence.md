# ADR-0091: Transaction Lifecycle Persistence

## Status

Accepted

## Context

Phase 22 的 `TransactionLifecycleManager` 状态仅存内存：进程重启后
TTL/心跳/超时信息丢失，长事务可能在重启后被误判或永久悬挂。

## Decision

- 生命周期状态持久化到元数据 Raft：新增生命周期命令
  ACTIVE（REGISTER）/ PREWRITE / HEARTBEAT / EXPIRED / COMMITTED /
  ROLLBACK；
- `TxnLifecycleRecord`：txn_id / start_ts / expire_at / state /
  decision_index，随元数据命令写入并 apply；
- 重启恢复：scan lifecycle → 恢复心跳 → 对 EXPIRED/超时事务自动 abort；
- 内存生命周期管理器作为读缓存，元数据 Raft 为权威。

## Alternatives

1. 仅内存：重启丢失 TTL 信息。
2. 独立持久化文件：与元数据 Raft 分叉。

## Consequences

优点：

- 重启后生命周期可恢复，无永久锁；
- 与决策排序（ADR-0087）共用 decisionIndex。

缺点：

- 心跳需要 Raft 提案（批量/节流可缓解）。

风险：

- 低；由 LifecyclePersistenceTest 验证。

## Implementation

- `transaction/lifecycle`：TxnLifecycleRecord；
- `transaction/metadata`：生命周期命令 + recoverLifecycle；
- 测试：LifecyclePersistenceTest。
