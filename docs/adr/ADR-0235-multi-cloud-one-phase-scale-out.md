# ADR-0235: Multi-Cloud One-Phase Scale-out

## Status

Accepted

## Context

Phase 45 的 `MultiCloudOnePhaseCommit` 支持单层云级仲裁。Phase 46 需要
云 × 区混合拓扑：多个云、每云多区，仲裁必须在云级与区级分层完成。

## Decision

新增 `MultiCloudOnePhaseScaleOut`：

- 拓扑注册：cloud → zones（每区主副本资格）；
- 分层仲裁：区内多数 → 云级合格；云级多数 → 跨云一阶段；
- 任一层次不合格 → 回退 2PC；
- 与 MultiCloudOnePhaseCommit / GlobalOnePhaseCommit /
  AsyncCommitCoordinator / resolved-ts 联动；
- 幂等由 txnId + 排序拓扑集合去重保证。

## Alternatives

1. 仅云级仲裁：忽略区内可用性；
2. 仅区级仲裁：忽略云级故障域；
3. 分层仲裁：兼顾两级故障域，选中。

## Consequences

优点：混合拓扑下提交路径最短；两级故障域均受控。

缺点：拓扑元数据复杂度上升。

风险：拓扑变化 → 资格重算 + 缓存失效，不产生错误提交。

## Implementation

`transaction/async/MultiCloudOnePhaseScaleOut` +
`src/test/java/io/tieringkv/transaction/async/MultiCloudOnePhaseScaleOutTest`、
`docs/transaction/multi-cloud-one-phase-scale-out.md`。
