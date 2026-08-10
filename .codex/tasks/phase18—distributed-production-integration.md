你现在负责实现 Tiering-KV Phase 18。

目标：
将 Phase 17 的 Region 生命周期能力进一步生产化，
完成 Region、Multi-Raft、Gateway、Migration、Cluster Deployment 的统一闭环。

严格遵循已有工程规范：

- 不破坏已有 Raft 共识模型；
- 不修改 MemTable/WAL/SSTable 核心存储语义；
- 所有新增能力必须通过 adapter/service/controller 接入；
- 保持 backward compatible；
- 所有关键设计必须先输出 ADR；
- 所有功能必须新增测试；
- 所有性能指标必须真实 benchmark；
- 禁止隐藏失败项。

当前基线：

Phase 17:

- 947 tests 全绿
- Region split/merge 完成
- Multi-Raft 完成
- Leader Transfer 完成
- Redis Gateway handler 完成
- Parallel Migration 完成

====================================================
Phase 18 Architecture Goal
====================================================

目标架构：

Client

↓

ClusterGateway(TCP)

↓

RequestRouter

↓

UnifiedRoutingLayer

        Region Route
              |
              |
        Slot Route Compatibility

↓

RegionManager

↓

RegionLifecycleService

        |
        |
        + Split
        + Merge
        + Transfer
        + Move

↓

Multi-Raft Group

        |
        |
        + RaftLog
        + Snapshot
        + Replication

↓

StorageEngine

====================================================
Task 1
统一 Region 与 Slot 路由模型
====================================================

新增 ADR：

ADR-0066 unified routing model

目标：

解决当前：

Region key-range routing

- Redis slot routing

双体系问题。

设计：

引入：

RoutingTable

包含：

RegionId

startKey

endKey

slotStart

slotEnd

epoch

leader

支持：

key -> slot

slot -> region

region -> raftGroup

要求：

- 单一权威路由表；
- epoch 校验；
- stale route 自动刷新；
- MOVED/ASK 语义统一。

新增：

UnifiedRouter

RoutingCache

RouteEpochGuard

测试：

UnifiedRoutingTest >=20

覆盖：

- key lookup
- slot lookup
- epoch mismatch
- stale cache
- route update

====================================================
Task 2
真实 TCP Redis Cluster Gateway
====================================================

新增：

NettyClusterGateway

支持：

协议：

RESP2

命令：

GET

SET

DEL

MGET

MSET

INFO

CLUSTER SLOTS

CLUSTER NODES

错误：

MOVED

ASK

TRYAGAIN

NOSCRIPT

连接模型：

EventLoop

↓

RESP Decoder

↓

CommandDispatcher

↓

UnifiedRouter

要求：

不能只是 handler 测试。

必须：

真实 socket client

真实 TCP server

新增：

GatewayIntegrationTest

至少：

30 cases

覆盖：

- connect
- pipeline
- concurrent client
- route redirect
- leader change

Benchmark:

目标：

GET >500K ops/s

SET >200K ops/s

====================================================
Task 3
Split/Merge 与 Raft Group 联动
====================================================

新增 ADR：

ADR-0067 region raft migration lifecycle

目标：

当前：

Region metadata split

但是：

数据迁移没有真正绑定 Raft group。

实现：

Split流程：

NORMAL

↓

SPLITTING

↓

Create child Region

↓

Create child Raft Group

↓

Snapshot Export

↓

Install Snapshot

↓

Catch Up Log

↓

Switch Routing

↓

Old Region Tombstone

Merge：

两个 Region：

停止写入窗口

创建目标 Raft Group

数据合并

日志追赶

epoch++

路由切换

新增：

RegionRaftMigrationManager

测试：

SplitRaftIntegrationTest >=30

MergeRaftIntegrationTest >=25

必须覆盖：

- leader split
- follower split
- migration failure
- restart recovery
- rollback

====================================================
Task 4
生产级 Migration
====================================================

升级：

RegionTransferManager

支持：

Chunk streaming

parallel workers

rate limit

checksum

resume

新增：

MigrationScheduler

能力：

- 自动调节 worker 数
- 根据 IO 压力降低速度
- 根据 backlog 增加并发

指标：

migration_bytes_total

migration_speed

migration_remaining

migration_error

Benchmark:

目标：

100B:

> 100MB/s

1KB:

> 300MB/s

====================================================
Task 5
跨节点真实部署
====================================================

新增：

docker-compose.cluster.yml

至少：

node1

node2

node3

每节点：

Gateway

Metadata Raft

Storage Raft

网络：

独立 container

加入：

tc netem

测试：

真实：

delay

loss

partition

restart

新增：

CrossMachineChaosTest

> =20

覆盖：

- leader kill
- network partition
- follower recovery
- snapshot catchup
- migration interruption

====================================================
Task 6
生产可观测性
====================================================

新增：

INFO CLUSTER

增加：

Region:

region_count

split_count

merge_count

Raft:

leader

term

commit_index

replication_lag

Migration:

speed

remaining

Gateway:

connection

qps

latency

增加：

MetricsExporter

格式：

Prometheus compatible

====================================================
ADR Requirements
====================================================

必须新增：

ADR-0066 unified routing

ADR-0067 region raft migration

ADR-0068 tcp gateway architecture

ADR-0069 cross machine deployment

ADR-0070 production metrics

====================================================
Testing Requirements
====================================================

新增测试：

最低：

150 cases

建议：

Routing:
20

Gateway:
30

Split/Merge Raft:
55

Migration:
20

Chaos:
20

Metrics:
10

最终：

Phase 18 total tests >1100

要求：

mvn test

必须：

0 failures

====================================================
Benchmark Requirements
====================================================

生成：

docs/benchmark/phase18-production-report.md

必须包含：

Gateway QPS

P50

P95

P99

Migration:

100B

1KB

10KB

Split:

1M

10M

Chaos recovery:

leader failover

snapshot restore

====================================================
Documentation
====================================================

新增：

docs/review/phase18-production-integration-review.md

包含：

Architecture

ADR

Implementation

Tests

Benchmark

Chaos

Limitations

Next Phase

====================================================
Git Workflow
====================================================

要求：

feature/phase18-production-integration

提交：

docs:
ADR

feat:
routing

feat:
gateway

feat:
migration

feat:
deployment

test:
phase18

perf:
benchmark

最后：

git merge --no-ff

checkpoint:

checkpoint-before-phase18

checkpoint-after-phase18

====================================================
完成标准
====================================================

Phase 18 完成必须满足：

✅ Region 与 Slot 单路由模型

✅ 真实 TCP Redis Gateway

✅ Split/Merge 与 Raft Group 联动

✅ Docker 三节点部署

✅ tc netem 混沌验证

✅ Migration 生产化

✅ Metrics 完整

✅ tests >1100

✅ benchmark 完整

✅ 所有限制如实记录

开始执行 Phase 18。
