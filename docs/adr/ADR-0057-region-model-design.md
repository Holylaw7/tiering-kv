# ADR-0057: Region Model Design

## Status

Accepted

## Context

Phase 16 需要将路由单元从静态 `ShardId` 演进为可分裂/合并的 `Region`，
以支持范围分区与放置控制。风险：旧路由携带过期元数据写入新拓扑，
必须用纪元（epoch）拒绝陈旧请求。

## Decision

- 新增 `cluster.region` 包：
  - `Region`：`regionId + [startKey, endKey) + leader + peers + epoch + state`；
  - `RegionState`：NORMAL / SPLITTING / MERGING / TOMBSTONE；
  - `RegionEpoch`：`(confVer, version)`——confVer 随 leader/成员变更，
    version 随范围变更；均从 1 起；
  - `RegionManager`：create / split / merge / route / epoch guard；
  - `StaleRegionEpochException`：旧纪元请求显式失败。
- 路由策略：Region 按 startKey 有序（无符号字典序），`floorEntry` 定位
  候选 + `contains` 校验；endKey 不包含（null 表示 +∞）。
- 分裂/合并：父/子 region 标记 TOMBSTONE（保留审计，不进路由表），
  子 region 纪元推进 confVer（分裂）或 confVer+version（合并）。
- 旧路由保护：`routeStrict(key, epoch)` 与 `guardEpoch(regionId, epoch)`
  拒绝 `requestEpoch.olderThan(currentEpoch)` 的请求。

## Alternatives

1. 直接复用 ShardId + SlotTable：无法表达范围分裂/合并，否决。
2. Consistent Hashing：范围查询与在线分裂复杂，否决（延续 ADR-0035 结论）。
3. 无纪元校验的 Region 表：分裂后旧路由可写错区域，否决。

## Consequences

优点：路由单元可演进（split/merge）；纪元保护防止旧路由写入；
TreeMap 路由 O(log n)。

缺点：需要维护 region 排序表与 tombstone 审计；分裂 id 派生规则
（`id*10+1/2`）为原型约定。

风险：跨 region 事务仍未支持（后续阶段）。

## Implementation

- `src/main/java/io/tieringkv/cluster/region/`
- `RegionManager` 后续接入 `INFO REGIONS` 与 `PlacementManager`
  （ADR-0060）。
