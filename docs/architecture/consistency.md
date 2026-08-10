# 一致性模型（Phase 19）

## Snapshot Isolation

- 读：仅见 `commitTS <= readTS` 的已提交版本；
- 写：prewrite 检测 commitTS > startTS 的已提交版本（写写冲突）；
- 读集跟踪：读过的键被后续提交覆盖 → 读写冲突；
- 未提交（LOCK/provisional）与回滚版本不可见。

## 隔离保证

- 无 dirty read：读者永不看到 LOCK 版本；
- 无 dirty write：写者 prewrite 必须持有锁；
- 无 lost update：同键并发写一方 WriteConflict；
- 无 phantom version：版本由 commitTS 单调保证；
- 无永久锁：TTL + 恢复清理。

## 故障模型

- 已提交事务永不丢失（底层持久化 + Raft 日志）；
- 未提交事务不虚假成功（journal future 显式失败）；
- 跨 Region 无部分提交（2PC rollback all）。
