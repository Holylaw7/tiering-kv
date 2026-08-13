# ADR-0228: Multi-Cloud Global One-Phase Commit

## Status

Accepted

## Context

Phase 44 的 `GlobalOnePhaseCommit` 覆盖同构区域（3 地/5 地）。Phase 45
需要扩展到多云：不同云提供商的区域同时参与，任一云不可用不得阻塞提交。

## Decision

新增 `MultiCloudOnePhaseCommit`：

- 多云主副本资格 → 跨云一阶段；
- 仲裁：多数云（> 云数/2）合格即可一阶段，少数云失败回退 2PC 路径；
- 任一云探测失败 → 该云降级为 2PC 参与方，不阻塞其他云；
- 与 GlobalOnePhaseCommit / AsyncCommitCoordinator / resolved-ts 联动；
- 幂等由 txnId + 排序云集合去重保证。

## Alternatives

1. 全部云必须合格才一阶段：单云故障阻塞全局；
2. 修改 AsyncCommitCoordinator 状态机：违反冻结协议；
3. 全量 2PC：正确但延迟高。

## Consequences

优点：多数云合格时提交路径最短；单云故障不阻塞。

缺点：仲裁语义需要云健康度元数据支撑。

风险：仲裁判定过期 → 回退 2PC 兜底，不产生错误提交。

## Implementation

`transaction/async/MultiCloudOnePhaseCommit` +
`src/test/java/io/tieringkv/transaction/async/MultiCloudOnePhaseCommitTest`、
`docs/transaction/multi-cloud-global-one-phase.md`。
