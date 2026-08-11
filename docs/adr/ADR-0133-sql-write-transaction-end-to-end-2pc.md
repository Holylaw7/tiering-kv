# ADR-0133: SQL Write Transaction End-to-End 2PC

## Status

Accepted

## Context

Phase 30 SqlTxnExecutor 的 COMMIT 为回调占位。需要接入真实 2PC
（GeoTransactionCoordinator / DistributedTxnRouter），禁止旁路事务
状态机。

## Decision

新增 `sql/txn/SqlTxn2PcBridge`：

1. WriteOp → TxnMessages.Mutation；
2. COMMIT → GeoTransactionCoordinator / DistributedTxnRouter 提交；
3. 生命周期与事务状态机对齐（BEGIN 语义由调用方管理）；
4. RBAC：WRITE 权限域校验（ADR-0110）。

## Alternatives

1. 直接写存储：绕过 2PC；
2. 仅本地事务：无法跨 Region。

## Consequences

优点：SQL 写与原生事务同语义。

缺点：桥接层需严格对齐状态机。

风险：回滚幂等由 2PC 保证。

## Implementation

代码影响范围：`sql/txn/SqlTxn2PcBridge` + 测试 +
`docs/sql/write-2pc.md`。
