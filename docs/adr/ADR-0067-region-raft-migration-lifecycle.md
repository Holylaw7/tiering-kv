# ADR-0067: Region Raft Migration Lifecycle

## Status

Accepted

## Context

Phase 17 的 split/merge 仅联动元数据与存储，未绑定独立 Raft 组：
子 region 没有自己的 Raft 日志/快照/复制。需要将数据迁移真正绑定
Raft group。

## Decision

Split 流程：

```text
NORMAL → SPLITTING → Create child Region → Create child Raft Group →
Snapshot Export → Install Snapshot → Catch Up Log → Switch Routing →
Old Region Tombstone
```

Merge 流程：

```text
停止写入窗口（MERGING）→ 创建目标 Raft Group → 数据合并 →
日志追赶 → epoch++ → 路由切换 → TOMBSTONE
```

- `RegionRaftMigrationManager`：编排以上流程，经 `RaftGroupManager`
  创建子组、`applyRawBatch` 装载快照、`MultiRaftNode` 注册/启动、
  `RoutingTable` 原子切换；
- 失败语义：迁移失败可回滚（旧 region 保持 NORMAL + 旧路由不变）；
- 恢复：重启后按任务状态续跑（checkpoint 语义）；
- 一致性：子组 leader 由 Raft 选举产生，路由 epoch 推进；
- 版本屏障：快照导出后新写入由 SplitWriteBuffer 缓冲并追赶。

## Alternatives

1. 仅元数据 split（Phase 17）：子 region 无独立日志，否决。
2. 拷贝后删除：窗口期双写风险，否决。
3. 全量复制再切换：窗口大，否决（沿用流式/并行迁移）。

## Consequences

优点：子 region 独立 Raft 日志/快照/复制；故障可回滚；epoch 保护。

缺点：创建子组需要额外 Raft 资源；编排状态机复杂度上升。

风险：快照导出与追赶窗口的写放大（由写缓冲 + 并行迁移控制）。

## Implementation

- `cluster/lifecycle/RegionRaftMigrationManager.java`
- 测试：SplitRaftIntegrationTest（≥30）/ MergeRaftIntegrationTest（≥25）。
