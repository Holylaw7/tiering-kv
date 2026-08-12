# ADR-0214: Cross-Region One-Phase Commit

## Status

Accepted

## Context

Phase 42 的 async commit 为单区一阶段（TD-079）；跨区事务仍需两阶段
RTT。

## Decision

1. `transaction/async/CrossRegionOnePhaseCommit`：跨区主副本一阶段
   提交 + 失败回退 2PC；
2. 与 AsyncCommitCoordinator / resolved-ts 联动；
3. 验收：一阶段矩阵 + 回退矩阵 + 幂等。

## Alternatives

1. 全量 2PC：跨区延迟高；
2. 无回退一阶段：失败不可恢复。

## Consequences

优点：跨区延迟降低。

缺点：需要回退路径。

风险：回退失败由重试兜底。

## Implementation

代码影响范围：`transaction/async/` + 测试 +
`docs/transaction/cross-region-one-phase.md`。
