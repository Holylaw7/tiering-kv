# ADR-0128: SQL Write Transactions

## Status

Accepted

## Context

Phase 28/29 SQL 为只读。写事务（BEGIN/SET/DELETE/COMMIT）需要路由
Region 并复用 2PC（GeoTransactionCoordinator），禁止绕过事务状态机。

## Decision

新增 `sql/txn/`：

1. 解析：BEGIN / SET / DELETE / COMMIT / ROLLBACK 子集；
2. `SqlTxnExecutor`：BEGIN → 写（路由 Region）→ COMMIT（2PC）；
3. RBAC：WRITE 权限域校验（ADR-0110）；
4. 单/跨 Region 正确性 + 回滚安全。

## Alternatives

1. 直接写存储：绕过事务语义；
2. 仅单 Region：无法跨区。

## Consequences

优点：SQL 写与原生事务同语义。

缺点：解析子集需边界测试。

风险：回滚路径需幂等。

## Implementation

代码影响范围：`sql/txn/` + 测试 +
`docs/sql/write-transactions.md`。
