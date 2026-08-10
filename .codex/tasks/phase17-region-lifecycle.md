# Phase 17 Development Prompt — Region Lifecycle & Distributed Storage Completion

## Phase 17：Region 生命周期与分布式存储完善

### 背景

Phase 16 已完成：

- Region 模型
- Multi-Raft 架构
- Zero-Copy 写路径
- PlacementManager
- Multi-RaftEndpoint
- Docker 部署基础
- Region 级故障隔离

当前系统已经具备 TiKV / Redis Cluster 类架构雏形。

进入 Phase 17：

目标：

> 完成 Region 生命周期闭环，使 Region 能够自主完成 Split / Merge / Transfer，并提升分布式迁移能力，实现生产级分片管理。

---

# 1. Architecture Goal

目标架构：

```
Client
 |
ClusterGateway
 |
RegionRouter
 |
RegionManager
 |
+-----------------------------+
| Region Lifecycle Controller |
+-----------------------------+
          |
          |
   +------+------+
   |             |
SplitManager  MergeManager
   |             |
RegionTransferManager
   |
Multi-Raft Group
   |
StorageEngine
```

新增：

```
RegionLifecycleService

├── SplitController
├── MergeController
├── TransferController
├── LeaderTransferManager
├── RegionStateMachine
└── PlacementCoordinator
```

---

# 2. ADR Requirements

## ADR-0061 Region Split Lifecycle

设计 Region 自动分裂。

Region 状态：

```
NORMAL

    |
    v

SPLITTING

    |
    v

SPLIT_READY

    |
    v

NORMAL
```

要求：

Region：

```
[startKey,endKey)
```

拆分：

```
old region

[startKey,endKey)


split key


new regions

[startKey,splitKey)

(splitKey,endKey)
```

必须保证：

- epoch +1
- route 原子切换
- 旧请求拒绝
- 新旧 leader 不冲突

新增：

```
RegionSplitTask

SplitPrepare

SplitSnapshot

SplitInstall

SplitCommit

SplitCleanup
```

---

# 3. ADR-0062 Region Merge

实现相邻 Region 合并。

条件：

```
Region A

+

Region B

=

Region C
```

要求：

检查：

- key 连续
- epoch 一致
- leader 状态正常

流程：

```
PREPARE

↓

LOCK

↓

TRANSFER DATA

↓

UPDATE META

↓

TOMBSTONE OLD REGION
```

旧 Region：

```
TOMBSTONE
```

禁止写入。

---

# 4. ADR-0063 Parallel Region Migration

解决 Phase16：

```
100B migration:

82MB/s
```

目标：

```
>150MB/s
```

实现：

```
RegionTransferManager

        |
        |
+-------+-------+
|       |       |
Worker Worker Worker
```

设计：

```
MigrationChunk

{
 startKey,
 endKey,
 checksum,
 version
}
```

要求：

- 多 worker
- checkpoint
- CRC
- retry
- pause/resume

线程模型：

```
transfer.worker.pool

size=min(8,CPU)
```

---

# 5. ADR-0064 Real Leader Transfer

Phase16：

> leader transfer 仅更新 epoch

Phase17：

实现真实 Raft leadership handoff。

流程：

```
Leader

 |
 | TransferLeadership(target)
 |
 v

Send TimeoutNow

 |
 v

Follower immediately election

 |
 v

New Leader
```

要求：

新增：

```
LeaderTransferManager


transferLeader(regionId,targetNode)
```

验证：

- 无数据丢失
- term 正确
- client 自动重试
- pending proposal 正确失败

---

# 6. ADR-0065 Placement Auto Balance

增强：

PlacementManager

新增：

```
BalanceScheduler
```

能力：

检测：

```
Region count imbalance

Leader imbalance

Disk pressure

CPU pressure
```

生成：

```
BalancePlan
```

例如：

```
move region-102

node1

-->

node3
```

限制：

- 不自动执行危险迁移
- 需要 epoch protection

---

# 7. Redis Cluster Gateway

Phase16:

```
ClusterMain
```

只有 Raft RPC。

Phase17：

增加访问层：

```
Redis Client

     |
     |
RESP Gateway

     |
ClusterRouter

     |
Region
```

支持：

Commands:

```
GET

SET

DEL

MGET

MSET

INFO

CLUSTER SLOTS
```

返回：

Redis Cluster:

```
MOVED slot node
```

兼容。

---

# 8. Observability

新增：

```
INFO REGIONS

INFO CLUSTER

INFO RAFT

INFO MIGRATION
```

Metrics:

Region:

```
region_count

split_count

merge_count

migration_bytes

migration_speed
```

Raft:

```
leader_transfer_total

election_total

proposal_latency
```

---

# 9. Chaos Testing

新增：

```
RegionChaosTest
```

覆盖：

## Split During Write

验证：

```
10000 writes

+

split

=

no lost data
```

## Merge During Failure

验证：

```
leader kill

merge

restart

recover
```

## Transfer During Network Delay

注入：

```
latency 200ms

packet loss 10%

leader transfer
```

## Multi Region Isolation

验证：

```
region A failure

does not affect

region B
```

---

# 10. Benchmark

新增：

```
phase17-region-report.md
```

指标：

## Split

目标：

```
100M keys

split <10s
```

## Merge

目标：

```
100M keys

merge <20s
```

## Migration

Phase16:

```
82MB/s
```

目标：

```
>150MB/s
```

## Leader Transfer

目标：

```
<500ms
```

## Redis Gateway

目标：

```
GET >100K ops/s

SET >50K ops/s
```

---

# 11. Tests

新增不少于：

```
120 tests
```

覆盖：

```
RegionSplitTest        >=25

RegionMergeTest        >=20

MigrationParallelTest  >=20

LeaderTransferTest     >=15

PlacementBalanceTest  >=15

RedisGatewayTest       >=15

ChaosTest              >=10
```

全量：

```
Phase1-17

>=900 tests

0 failures
```

---

# 12. Git Strategy

提交：

```
docs: add ADR-0061 region split lifecycle

feat: implement region split controller

feat: implement region merge

feat: add parallel migration

feat: implement raft leader transfer

feat: add redis cluster gateway

test: add phase17 validation

perf: add phase17 benchmark
```

最终：

```
checkpoint-before-phase17

checkpoint-after-phase17
```

合并：

```
merge: integrate Phase 17 region lifecycle
```

---

# Phase 17 Success Criteria

必须满足：

| 能力               | 目标              |
| ------------------ | ----------------- |
| Region Split       | 完成              |
| Region Merge       | 完成              |
| Parallel Migration | >150MB/s          |
| Leader Transfer    | <500ms            |
| Redis Gateway      | 支持 Cluster 协议 |
| Placement Balance  | 可生成迁移计划    |
| Chaos              | Region级故障恢复  |
| Tests              | 900+ 全绿         |

---
