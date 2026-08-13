# ADR-0249: Multi-Organization Federation Arbitration

## Status

Accepted

## Context

Phase 47 的 `GlobalUnifiedOnePhaseArbitration` 覆盖单组织多云拓扑。
Phase 48 需要跨组织边界：多组织联邦仲裁。

## Decision

新增 `MultiOrgFederationArbitration`：

- 组织边界发现：cloud → organization 映射注册；
- 组织级仲裁：组织内云多数 → 组织合格；组织多数 → 联邦一阶段；
- 任一组织不合格 → 回退 2PC；
- 与 GlobalUnifiedOnePhaseArbitration / MultiCloudOnePhaseScaleOut /
  AsyncCommitCoordinator / resolved-ts 联动；
- 幂等由 txnId + 组织快照去重保证。

## Alternatives

1. 单组织仲裁：无法跨边界；
2. 全量 2PC：正确但延迟高；
3. 组织级联邦仲裁：两级边界受控，选中。

## Consequences

优点：多组织一阶段最短路径；组织边界故障域受控。

缺点：组织元数据复杂度上升。

风险：组织映射变化 → 资格重算 + 缓存失效，不产生错误提交。

## Implementation

`transaction/async/MultiOrgFederationArbitration` +
`src/test/java/io/tieringkv/transaction/async/MultiOrgFederationArbitrationTest`、
`docs/transaction/multi-org-federation-arbitration.md`。
