# ADR-0065: Placement Auto Balance

## Status

Accepted

## Context

多 Region 集群会出现 Region 数、leader、磁盘、CPU 不均衡。
需要自动检测并生成迁移计划，但不自动执行危险迁移。

## Decision

- `BalanceScheduler`：周期检测四类压力：
  - region count imbalance（每节点 region 数）；
  - leader imbalance（每节点 leader 数）；
  - disk pressure（region_size 总和超阈值）；
  - cpu pressure（外部指标，本阶段用可注入的负载快照）；
- 生成 `BalancePlan`：`move region-102 node1 -> node3` 列表；
- 安全约束：只移动 NORMAL region；目标节点必须在 peers；epoch
  保护（计划携带当前 epoch，执行前校验）；每轮计划上限可配置；
- 不自动执行：`executePlan` 仅返回校验后的 leader 转移建议
  （真实执行走 LeaderTransferManager，且需人工/编排确认）。

## Alternatives

1. 自动执行 rebalance：危险迁移风险，否决。
2. 仅统计无计划：无法指导运维，否决。
3. 中心化调度器（PD 模式）：超出本阶段，后续演进。

## Consequences

优点：可观测 + 可执行计划；epoch 保护避免陈旧计划。

缺点：leader 均衡通过真实交接完成，region 数据搬迁仍需人工触发
并行迁移。

风险：压力指标注入口径需在真实部署校准。

## Implementation

- `cluster/placement/BalanceScheduler.java`、`BalancePlan.java`
- 测试：PlacementBalanceTest（≥15）。
