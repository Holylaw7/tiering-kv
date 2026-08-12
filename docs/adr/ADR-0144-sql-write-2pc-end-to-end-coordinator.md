# ADR-0144: SQL Write 2PC End-to-End Coordinator

## Status

Accepted

## Context

Phase 32 的 SQL 写 2PC 仍通过函数式回调接入提交路径，未直接驱动
GeoTransactionCoordinator 的真实决策日志与跨地域 prewrite/commit 状态机。
商业化与自治能力依赖"SQL 写入 = 原生 2PC"语义等价。

## Decision

1. `sql/txn/SqlTxnCoordinatorAdapter`：WriteOp 分组 → 真实
   GeoTransactionCoordinator（begin/commit/rollback）；
2. 提交/回滚/幂等/决策日志与原生 2PC 对齐；禁止旁路事务状态机；
3. 验收：跨地域写事务端到端（提交、回滚、恢复、幂等矩阵）。

## Alternatives

1. 保留函数回调：无决策日志，恢复语义不完整；
2. SQL 引擎内置 2PC：与现有协调器重复。

## Consequences

优点：SQL 写与原生 2PC 同源，恢复语义一致。

缺点：需要真实协调器实例（决策日志 + 区域客户端）。

风险：区域故障时提交失败按决策日志恢复，测试需覆盖。

## Implementation

代码影响范围：`sql/txn/SqlTxnCoordinatorAdapter` + `GeoTransactionCoordinator`
接线 + 测试 + `docs/sql/2pc-coordinator.md`。
