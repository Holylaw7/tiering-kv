# ADR-0062: Region Merge

## Status

Accepted

## Context

Region 收缩（删除大量数据后）需要合并相邻 Region 减少管理开销。
合并必须检查：键连续、epoch 一致、leader 状态正常；旧 Region 禁止写入。

## Decision

- 流程：PREPARE → LOCK → TRANSFER DATA → UPDATE META → TOMBSTONE；
- `MergeController.merge(leftId, rightId)`：
  - PREPARE：校验 left.end == right.start、两 region NORMAL、
    leader 正常（非空）；
  - LOCK：两侧状态 → MERGING（写入拒绝）；
  - TRANSFER DATA：右 region 数据零拷贝迁移到左存储
    （applyRawBatch，按版本 latest-wins）；
  - UPDATE META：RegionManager.mergeRegion（合并 region epoch
    confVer+1 & version+1，路由指向合并 region）；
  - TOMBSTONE：两侧旧 region 标记 TOMBSTONE（拒绝写入，
    guardEpoch 返回 false）；
- 合并后 leader：取左 leader（元数据），真实 Raft 交接见 ADR-0064。

## Alternatives

1. 复制-删除两阶段：存在窗口期双写，否决。
2. 仅元数据合并：数据残留右 region 存储，否决。

## Consequences

优点：合并原子、数据收敛到单一 region；旧 region 立即失效。

缺点：合并期间两侧写入被拒（运维窗口）；leader 交接为元数据级
（真实交接见 ADR-0064）。

风险：合并失败需回滚到 PREPARE（控制器保留原始元数据）。

## Implementation

- `cluster/lifecycle/merge/MergeController.java`
- 测试：RegionMergeTest（≥20）。
