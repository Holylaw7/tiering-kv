你现在负责执行 Tiering-KV Phase 16：

Multi-Raft Architecture Evolution

目标：

将 Tiering-KV 从：

Shard + Single Raft Group

演进为：

Region + Multi-Raft Group + Placement Control

严格保持：

1. Raft Consensus 语义不变
2. WAL/SSTable 数据格式兼容
3. 已有 API 不破坏
4. 所有架构变化 ADR 化
5. Benchmark 必须真实

==================================================
Phase 15 当前状态
==================================================

已完成：

- Streaming Migration
- Async Raft Proposal
- mTLS Rotation
- Chaos Testing
- Cluster Metrics

测试：

650 / 650

当前限制：

TD-033:

小对象迁移：

100B:

59.8MB/s

瓶颈：

Mutation 创建

KeyValueEntry 拷贝

Segment Lock

TD-035:

跨机器验证不足

==================================================
Phase 16 总目标
==================================================

引入：

Region Layer

架构：

Client

|

ClusterRouter

|

RegionRouter

|

Region

|

RaftGroup

|

StorageEngine

从：

ShardId

升级为：

RegionId

==================================================
Part 1
Region Abstraction
==================================================

新增：

cluster.region

核心对象：

Region

字段：

regionId

startKey

endKey

leader

peers

epoch

state

状态：

NORMAL

SPLITTING

MERGING

TOMBSTONE

---

新增：

RegionManager

负责：

- create region
- split region
- merge region
- route lookup

新增：

RegionEpoch

用于：

防止：

旧路由写入

包含：

confVer

version

新增 ADR:

ADR-0057-region-model-design.md

内容：

- region abstraction
- epoch consistency
- routing strategy

测试：

> =20

覆盖：

- region create
- route
- epoch conflict
- stale request

==================================================
Part 2
Multi-Raft Group
==================================================

目标：

每个 Region 独立 Raft。

当前：

Shard

|

Raft

升级：

Region A

|

RaftGroup A

Region B

|

RaftGroup B

---

新增：

RaftGroupManager

负责：

create

destroy

schedule

---

新增：

MultiRaftNode

要求：

支持：

多个 RaftNode 并行运行

共享：

RPC Transport

隔离：

Log

State

Snapshot

---

新增 ADR:

ADR-0058-multi-raft-design.md

内容：

- group isolation
- resource sharing
- scheduling

测试：

> =30

覆盖：

- multiple raft
- leader election
- snapshot
- failure isolation

==================================================
Part 3
Zero Copy Batch Write
==================================================

解决：

TD-033

目标：

100B migration:

> 100MB/s

---

新增：

RawMutation

结构：

byte[] key

byte[] value

version

ttl

禁止：

Mutation

↓

KeyValueEntry

↓

copy

改成：

source buffer

↓

ownership transfer

↓

MemTable

---

新增：

MemTable.applyRawBatch

要求：

一次：

segment lock

批量：

1024 entries

减少：

对象创建

数组复制

新增 ADR:

ADR-0059-zero-copy-write-path.md

测试：

> =20

覆盖：

- raw batch
- ownership
- concurrent write
- recovery

Benchmark:

比较：

Phase15

vs

Phase16

指标：

100B migration

1KB migration

batch write

目标：

100B:

> 100MB/s

==================================================
Part 4
Real Cross Machine Deployment
==================================================

建立：

Docker Compose

节点：

node1

metadata

node2

region leader

node3

replica

要求：

独立：

JVM

Disk

Network

---

网络模拟：

tc netem

测试：

latency:

50ms

100ms

loss:

1%

5%

partition

disk slow

kill -9

新增：

ChaosClusterTest

> =20

==================================================
Part 5
Placement Control Prototype
==================================================

新增：

PlacementManager

功能：

region distribution

balance check

leader transfer

暂不实现：

自动 rebalance

新增 ADR：

ADR-0060-placement-control.md

测试：

> =10

==================================================
Observability
==================================================

新增指标：

Region:

region_count

region_size

region_split_count

Raft:

raft_group_count

leader_distribution

Migration:

region_move_bytes

INFO:

INFO REGIONS

输出：

region

leader

epoch

size

==================================================
Testing Requirements
==================================================

新增：

Region:

20

MultiRaft:

30

ZeroCopy:

20

Chaos:

20

Placement:

10

Total:

> =100

要求：

mvn test

Phase1-16:

全部通过

==================================================
Benchmark
==================================================

生成：

docs/benchmark/

phase16-multiraft-report.md

必须包含：

1.

Phase15 vs Phase16

2.

ZeroCopy migration

3.

MultiRaft throughput

4.

Cross machine latency

5.

Failure recovery

目标：

Migration:

100B >100MB/s

MultiRaft:

线性扩展趋势

==================================================
Git
==================================================

提交：

docs ADR

feat region

feat multiraft

feat zerocopy

feat placement

test

benchmark

最终：

merge --no-ff

commit:

merge: integrate Phase 16 multi raft architecture

创建：

checkpoint-before-phase16

checkpoint-after-phase16

==================================================
最终输出
==================================================

生成：

Phase 16 Completion Report

包含：

1 Architecture

2 ADR

3 Implementation

4 Tests

5 Benchmark

6 Chaos

7 Limitations

8 Next Phase

禁止：

隐藏未达标指标。

开始执行 Phase 16。
