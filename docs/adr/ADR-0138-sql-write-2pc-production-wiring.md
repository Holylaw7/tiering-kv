# ADR-0138: SQL Write 2PC Production Wiring

## Status

Accepted

## Context

Phase 31 SqlTxn2PcBridge 为回调占位。需要接入真实 Geo/分布式 2PC：
BEGIN → WriteOp → prewrite/commit → COMMIT，与原生事务语义等价。

## Decision

新增 `sql/txn/SqlTxn2PcExecutor`：

1. BEGIN 生命周期由协调器管理；
2. COMMIT → GeoTransactionCoordinator / DistributedTxnRouter 真实提交；
3. 回滚幂等（2PC 保证）；
4. RBAC WRITE 校验在提交前执行。

## Alternatives

1. 保持回调占位：无法端到端验证；
2. 直接写存储：绕过事务状态机。

## Consequences

优点：SQL 写与原生 2PC 同语义。

缺点：协调器生命周期需严格对齐。

风险：跨 Region 提交失败依赖 2PC 恢复。

## Implementation

代码影响范围：`sql/txn/SqlTxn2PcExecutor` + 测试 +
`docs/sql/write-2pc-production.md`。
