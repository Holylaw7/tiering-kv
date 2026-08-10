# ADR-0060: Placement Control

## Status

Accepted

## Context

多 Region 部署后需要：region 分布可见、均衡度可检查、leader 可转移。
自动 rebalance 涉及跨 Region 数据搬迁与路由切换，复杂度高，本阶段
暂不实现（原型边界）。

## Decision

- `cluster.placement.PlacementManager`：
  - `distribution()`：每节点承载的 NORMAL region 列表（含 leader 节点）；
  - `balanceSkew()` / `isBalanced(maxSkew)`：最大-最小 region 数差异；
  - `transferLeader(regionId, newLeader)`：校验 newLeader ∈ peers，
    经 `RegionManager.transferLeader` 更新，epoch confVer 推进；
- `RegionManager.transferLeader`：leader 变更持久到路由表（旧路由由
  epoch guard 拒绝）；
- 可观测性（ADR-0056 扩展）：
  - `RegionMetricsRegistry`：region_count / region_size /
    region_split_count / raft_group_count / leader_distribution /
    region_move_bytes；
  - `RegionInfo`：`INFO REGIONS` 输出 region/leader/epoch/size/state。
- 明确不做：自动 rebalance（后续阶段）。

## Alternatives

1. 自动 rebalance（即时）：需跨 Region 搬迁 + 路由原子切换，风险高，否决。
2. 中心化 placement 服务（PD 模式）：超出本阶段范围，否决。
3. 仅静态分布无转移能力：无法演示 leader 迁移，否决。

## Consequences

优点：分布与均衡可见；leader 转移受 epoch 保护；为自动 rebalance 提供
基础 API。

缺点：leader 转移只更新元数据，不触发真实 Raft leader 交接
（真实交接需 Raft 层 step-down + 选举，后续阶段）。

风险：无自动 rebalance 时热点/倾斜需人工介入。

## Implementation

- `cluster/placement/PlacementManager.java`
- `cluster/region/RegionManager.transferLeader`
- `cluster/region/RegionMetricsRegistry.java`、`RegionInfo.java`
- 测试：PlacementManagerTest（12）+ RegionObservabilityTest（11）。
