# ADR-0242: Global Unified One-Phase Arbitration

## Status

Accepted

## Context

Phase 46 的 `MultiCloudOnePhaseScaleOut` 依赖调用方提供固定拓扑。
Phase 47 需要任意云 × 区拓扑的自动发现与动态仲裁。

## Decision

新增 `GlobalUnifiedOnePhaseArbitration`：

- 拓扑自动发现：从注册表聚合任意云 × 区结构；
- 动态仲裁：多数云 + 多数区分层判定（无需调用方传拓扑）；
- 任一层次不合格 → 回退 2PC；
- 与 MultiCloudOnePhaseScaleOut / MultiCloudOnePhaseCommit /
  AsyncCommitCoordinator / resolved-ts 联动；
- 幂等由 txnId + 发现拓扑快照去重保证。

## Alternatives

1. 固定拓扑：无法适应运行时变化；
2. 全量 2PC：正确但延迟高；
3. 仅云级仲裁：忽略区级故障域。

## Consequences

优点：任意拓扑自动仲裁；两级故障域受控。

缺点：拓扑发现一致性需版本化（发现版本参与缓存键）。

风险：拓扑变化竞态 → 资格重算 + 缓存失效，不产生错误提交。

## Implementation

`transaction/async/GlobalUnifiedOnePhaseArbitration` +
`src/test/java/io/tieringkv/transaction/async/GlobalUnifiedOnePhaseArbitrationTest`、
`docs/transaction/global-unified-one-phase-arbitration.md`。
