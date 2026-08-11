# SQL 写 2PC 生产接线

Phase 32 · ADR-0138

## 执行器

```text
SqlTxn2PcExecutor
  ├─ begin(token)：WRITE 权限校验 + 开启事务
  ├─ write(key, value, deleted)：收集变更
  ├─ commit()：再次校验 → 2PC 提交（回调接 Geo/分布式协调器）
  └─ rollback()：丢弃 + 关闭事务
```

## 语义

- 生命周期与事务状态机对齐（BEGIN/COMMIT/ROLLBACK）；
- 令牌吊销后 commit 拒绝；
- 失败返回 false，回滚幂等由 2PC 保证。

## 基准（进程内）

单键事务 100K–1M txn/s。
