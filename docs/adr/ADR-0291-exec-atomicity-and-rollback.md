# ADR-0291: EXEC Atomicity & Rollback

## Status

Accepted

## Context

EXEC 顺序执行，失败可能半程提交；跨段无回滚。

## Decision

EXEC 收敛为预检 + 回滚 + 日志：

- 写命令预检（arity/类型）失败 → 整体拒绝并返回错误；
- 执行前记录受影响键旧值快照；
- 任一步失败 → 回滚已应用键（旧值恢复），结果数组含错误；
- `ExecJournal` 登记（txnId、命令数、outcome、时间）；
- 跨段仍顺序执行，回滚保证整体一致（快照恢复）。

## Alternatives

1. 全量 MVCC 2PC：改动事务状态机，禁止；
2. 无回滚：半程提交；
3. 全局锁：并发度低。

## Consequences

优点：失败一致、可审计。

缺点：快照回滚 O(受影响键)。

风险：回滚本身失败需登记 FAILED_ROLLBACK。

## Implementation

`ExecJournal`、ExecCommand 快照/回滚 +
`src/test/java/io/tieringkv/command/ExecAtomicityRollbackTest.java`。
