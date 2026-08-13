# EXEC Atomicity & Rollback

## 流程

1. WATCH 版本校验（不一致 abort）；
2. 受影响键旧值快照；
3. 顺序执行队列；任一步返回错误 → 回滚快照；
4. ExecJournal 登记 outcome（SUCCESS / ROLLED_BACK /
   FAILED_ROLLBACK）。

## 限制

跨段仍顺序执行，回滚保证整体一致；严格跨命令原子事务使用 MVCC 2PC
路径（Phase 19+）。
