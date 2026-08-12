# ADR-0209: Async Commit & Resolved Timestamp

## Status

Accepted

## Context

2PC 提交需两阶段 RTT；单区事务可一阶段提交；跨区读需要 resolved-ts
保证一致性读水位。

## Decision

1. `transaction/async/AsyncCommitCoordinator`：单区事务一阶段提交 +
   回退 2PC；
2. `transaction/async/ResolvedTimestampService`：跨区 resolved-ts
   推进 + 查询；
3. 验收：一阶段矩阵 + 回退矩阵 + resolved-ts 单调性。

## Alternatives

1. 全量 2PC：单区延迟高；
2. 无 resolved-ts：跨区读陈旧。

## Consequences

优点：单区延迟降低，跨区读水位一致。

缺点：需要回退路径。

风险：回退失败由重试兜底。

## Implementation

代码影响范围：`transaction/async/` + 测试 +
`docs/transaction/async-commit.md`。
