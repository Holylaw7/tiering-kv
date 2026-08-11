# SQL 写事务

Phase 30 · ADR-0128

## 语法

```sql
BEGIN
SET 'user:1' = 'v1'
DELETE FROM kv WHERE key = 'user:2'
COMMIT  /  ROLLBACK
```

## 执行

- `SqlTxnParser`：BEGIN/SET/DELETE/COMMIT/ROLLBACK 子集；
- `SqlTxnExecutor`：收集 WriteOp（region 路由）→ COMMIT 回调
  （调用方接 2PC / GeoTransactionCoordinator）；
- RBAC：WRITE 权限域由调用方校验（ADR-0110）。

## 基准（进程内）

单键事务 6.25K–143K txn/s。

## 限制

- 解析为子集（无表达式/条件写）；
- COMMIT 回调需接真实 2PC（Phase 31 端到端接线）。
