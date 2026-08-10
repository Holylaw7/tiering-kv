# ADR-0061: Region Split Lifecycle

## Status

Accepted

## Context

Region 需要在运行期自动分裂以应对键空间增长。分裂必须保证：epoch +1、
路由原子切换、旧请求拒绝、新旧 leader 不冲突、分裂期间写入不丢失。

## Decision

- 状态机：NORMAL → SPLITTING → SPLIT_READY → NORMAL；
- `RegionSplitTask` 五阶段：
  - PREPARE：校验 NORMAL + splitKey 严格在区间内 + 记录版本屏障；
  - SNAPSHOT：按 [start,split) / [split,end) 生成 SplitSnapshot；
  - INSTALL：子 region 存储装载（零拷贝 applyRawBatch）+ 元数据分裂
    （epoch confVer+1）；
  - COMMIT：路由原子切换（RegionManager 路由表）、父 region TOMBSTONE；
  - CLEANUP：释放快照与写缓冲；
- 分裂期间写入：SPLITTING 期间写入缓冲到 SplitWriteBuffer，COMMIT 时
  按键分发到对应子 region（版本屏障防止覆盖更新）；
- 旧请求拒绝：子 region epoch 高于旧 epoch，`routeStrict`/`guardEpoch`
  拒绝旧路由写入；
- leader 冲突：子 region 继承父 leader 或显式指定，不得在同一节点
  同时产生两个冲突 leader（元数据层保证）。

## Alternatives

1. 一次性全量复制再切换：分裂窗口长、数据量翻倍，否决。
2. 无写缓冲直接分裂：并发写入丢失，否决。
3. 仅元数据分裂共享存储：数据搬迁延迟到后台，本阶段提供缓冲路径，
  后续可演进。

## Consequences

优点：分裂原子可恢复；并发写入不丢失；epoch 保护旧路由。

缺点：写缓冲在窗口期占用内存；子 region 存储为独立 MemTable
（生产可演进为独立 Raft 组）。

风险：窗口期写入压力大时缓冲膨胀（后续加背压）。

## Implementation

- `cluster/lifecycle/RegionLifecycleService.java`
- `cluster/lifecycle/split/`（SplitController / RegionSplitTask /
  SplitSnapshot / SplitWriteBuffer）
- 测试：RegionSplitTest（≥25）。
