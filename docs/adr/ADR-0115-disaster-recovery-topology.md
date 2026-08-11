# ADR-0115: Disaster Recovery Topology

## Status

Accepted

## Context

多地域复制需要容灾拓扑（主/备/仲裁）与可演练的切换流程，RTO/RPO 需
可测量。

## Decision

新增 `dr/`：

1. `DrTopology`：primary / secondary / observer 角色与复制模式映射；
2. `DrSwitchPlanner`：计划内切换（决策日志补放）与故障切换计划；
3. `DrDrillRunner`：演练执行与 RTO/RPO 采样；
4. 混沌验证：主区故障 → 备区接管 → 一致性校验。

## Alternatives

1. 无拓扑模型：切换过程不可编排；
2. 仅手工切换：无法量化 RTO/RPO。

## Consequences

优点：切换可计划、可演练、可量化。

缺点：故障切换 RPO 受复制模式限制（async 有窗口）。

风险：切换期间双主短暂并存需 CRDT/仲裁兜底。

## Implementation

代码影响范围：`dr/` + `DrChaosTest` + `docs/dr/*`。
