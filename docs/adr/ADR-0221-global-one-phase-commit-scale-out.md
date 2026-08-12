# ADR-0221: Global One-Phase Commit Scale-out

## Status

Accepted

## Context

Phase 43 的 `CrossRegionOnePhaseCommit` 只做主副本资格判定（TD-079
JVM 关闭方向）。Phase 44 需要把一阶段扩展到 3 地 / 5 地全局规模，
同时保持「任一区域不合格回退 2PC」的安全语义。

## Decision

新增 `GlobalOnePhaseCommit`：

- 多区域主副本资格 → 全部合格走全局一阶段；
- 任一区域不合格 / 探测失败 → 回退 2PC；
- 与 CrossRegionOnePhaseCommit 复用资格模型，与 AsyncCommitCoordinator /
  resolved-ts 联动：一阶段成功后推进全局 resolved 水位；
- 幂等由 txnId 去重保证。

## Alternatives

1. 修改 AsyncCommitCoordinator 状态机：风险高，违反冻结协议；
2. 每区域独立一阶段：无法保证全局原子性；
3. 全量 2PC：正确但延迟高。

## Consequences

优点：主副本全部合格时提交路径最短；回退路径保持 2PC 安全。

缺点：需要维护主副本资格元数据；跨区时钟依赖 TSO 单调性。

风险：主副本资格过期 → 回退 2PC 兜底，不产生错误提交。

## Implementation

`transaction/async/GlobalOnePhaseCommit` +
`src/test/java/io/tieringkv/transaction/async/GlobalOnePhaseCommitTest`、
`docs/transaction/global-one-phase-commit.md`。
