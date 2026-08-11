# SQL 写事务 2PC 端到端

Phase 31 · ADR-0133

## 桥接

```text
SqlTxnExecutor WriteOp
  → SqlTxn2PcBridge（→ TxnMessages.Mutation）
  → commit2pc（GeoTransactionCoordinator / DistributedTxnRouter）
```

## 语义

- BEGIN/COMMIT 生命周期与事务状态机对齐；
- 删除保留 deleted 标志；
- 失败返回 false，由 2PC 回滚幂等兜底；
- RBAC WRITE 权限域由调用方校验（ADR-0110）。

## 基准（进程内）

单键事务 16.7K–167K txn/s。

## 限制

- 桥接回调需接真实 Geo/分布式 2PC（本阶段为语义闭环）。
