# ADR-0088: Transaction TTL and Heartbeat

## Status

Accepted

## Context

长事务或崩溃协调器会长期占用锁与 provisional 版本，阻塞其他事务；
需要事务 TTL 与心跳续约，超时自动 abort，防止永久锁。

## Decision

- `TransactionLifecycleManager`：ACTIVE → PREWRITE → COMMITTED / ROLLED_BACK
  / EXPIRED 生命周期；begin/commit/rollback/abort 统一登记；
- `TxnTimeoutScheduler`：定时扫描活跃事务，超过
  `txn.ttl.seconds` / `txn.max-duration` 自动 abort（走 router.rollback +
  metadata ROLLBACK）；
- `TxnHeartbeatManager`：client → coordinator 心跳，coordinator 刷新
  TTL 并向 participants 发 HEARTBEAT RPC（锁 TTL 续约）；
- 配置：`txn.ttl.seconds`、`txn.max-duration`（TieringConfig.Txn）。

## Alternatives

1. 无 TTL：锁永久悬挂，靠人工清理。
2. 仅靠 participant 锁 TTL：协调器崩溃后参与者各自超时，无法协调回滚。
3. 心跳写 Raft：成本高，收益与锁级心跳相当。

## Consequences

优点：

- 长事务自动 abort，无永久锁；
- 心跳续约保证活跃长事务不被误杀。

缺点：

- 需要生命周期管理器与调度线程。

风险：

- 低；由 LongTransactionTimeoutTest / HeartbeatExtensionTest /
  ExpiredTransactionRecoveryTest 验证。

## Implementation

- `transaction/lifecycle`：TransactionLifecycleManager、
  TxnTimeoutScheduler、TxnHeartbeatManager；
- `config`：TieringConfig.Txn。
