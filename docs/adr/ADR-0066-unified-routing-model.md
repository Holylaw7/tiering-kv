# ADR-0066: Unified Routing Model

## Status

Accepted

## Context

当前存在双路由体系：Region 键范围路由（Phase 16/17）与 Redis slot 路由
（Phase 11/17 网关）。双体系导致路由不一致、CLUSTER SLOTS 与键路由
脱节。需要单一权威路由表。

## Decision

- `RoutingTableEntry`：regionId + [startKey,endKey) + [slotStart,slotEnd] +
  epoch + leader + raftGroupId；
- `UnifiedRouter`（接口）：
  - `key -> slot`（CRC16/CCITT）→ region；
  - `slot -> region`（区间映射）；
  - `region -> raftGroup`；
- `RoutingTable`：单一权威实现（TreeMap 键范围 + slot 区间数组 +
  epoch 版本），支持 `update` 原子替换与 `version` 递增；
- `RoutingCache`：key→entry 缓存，命中校验 epoch；陈旧自动回源刷新
  （stale route auto refresh）；
- `RouteEpochGuard`：请求携带 epoch，与当前不符 → 刷新后拒绝
  （MOVED/ASK 语义由网关统一输出）；
- 网关重定向：目标 region 非本地 → `MOVED slot host:port`；
  迁移中 → `ASK`；集群未就绪/重试 → `TRYAGAIN`。

## Alternatives

1. 双表并存 + 转换层：一致性风险高，否决。
2. 仅 slot 路由：丢失键范围语义（split/merge 无依托），否决。
3. 仅键范围路由：无法兼容 Redis Cluster 协议，否决。

## Consequences

优点：单一权威路由；epoch 校验 + 缓存自刷新；MOVED/ASK/TRYAGAIN
语义统一。

缺点：路由表更新需跨组件同步（RegionManager / 网关 / Multi-Raft）。

风险：缓存过期窗口内可能返回一次陈旧重定向（由客户端重试收敛）。

## Implementation

- `cluster/routing/`（RoutingTableEntry / UnifiedRouter / RoutingTable /
  RoutingCache / RouteEpochGuard）
- 测试：UnifiedRoutingTest（≥20）。
