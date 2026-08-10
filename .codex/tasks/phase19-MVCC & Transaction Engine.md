你现在负责实现 Tiering-KV Phase 19。

Phase 19 正式进入数据库内核阶段。

核心目标：

在不破坏 Phase 1–18 已有存储、Raft、Multi-Raft、Region、Gateway、
Migration 和路由体系的前提下，引入：

1. MVCC 多版本数据模型
2. Timestamp / Hybrid Logical Clock
3. Snapshot Read
4. 乐观事务
5. Prewrite / Commit / Rollback
6. Lock 管理
7. Write Conflict 检测
8. Transaction Recovery
9. MVCC Garbage Collection
10. Region/Raft 与 Transaction 的集成

最终目标：

将 Tiering-KV 从：

Distributed KV Storage

升级为：

Distributed Transactional KV Storage

本阶段不实现 SQL。

本阶段不实现 Secondary Index。

本阶段重点建立可靠的 Transaction/MVCC 内核。

====================================================
一、当前基线
====================================================

Phase 18 已完成：

- 1112/1112 tests 全绿
- UnifiedRoutingLayer
- Region + Slot unified routing
- TCP Redis Gateway
- Multi-Raft
- Region Split/Merge
- Parallel Migration
- Leader Transfer
- Production Metrics
- Docker Cluster topology
- Chaos validation

核心架构：

Client
↓
ClusterGateway
↓
RequestRouter
↓
UnifiedRoutingLayer
↓
RegionManager
↓
Multi-Raft Group
↓
StorageEngine

Phase 1–18 的：

MemTable
WAL
SSTable
Raft
RaftLog
Snapshot
Region
Multi-Raft

必须保持兼容。

====================================================
二、Phase 19 总体架构
====================================================

目标架构：

Client
↓
TransactionClient
↓
TransactionCoordinator
↓
RegionRouter
↓
MVCC Layer
├── LockTable
├── WriteRecord
├── MVCC Reader
├── Snapshot
└── GC
↓
RaftGroup
↓
StorageEngine

新增核心组件：

TimestampOracle
HybridLogicalClock

MvccKey

MvccVersion

MvccEntry

MvccReader

MvccWriter

LockManager

Transaction

TransactionManager

PrewriteExecutor

CommitExecutor

RollbackExecutor

ConflictDetector

SnapshotReader

MvccGcManager

====================================================
三、ADR
====================================================

必须先设计并提交以下 ADR：

ADR-0071 MVCC Data Model

ADR-0072 Timestamp and HLC

ADR-0073 Transaction Protocol

ADR-0074 Lock and Conflict Detection

ADR-0075 MVCC Garbage Collection

ADR-0076 Transaction Recovery

每个 ADR 必须说明：

Context

Problem

Decision

Alternatives

Consistency Model

Failure Model

Recovery Model

Performance Impact

Compatibility

禁止直接编码后补 ADR。

====================================================
四、MVCC 数据模型
====================================================

设计：

Logical Key：

user:100

内部版本：

user:100
├── commitTS=105
├── commitTS=98
└── commitTS=91

底层存储 key：

[userKey][commitTS]

必须支持：

PUT

DELETE

READ

SCAN

MvccVersion：

startTS

commitTS

MvccEntry：

key

value

startTS

commitTS

writeType

writeType：

PUT

DELETE

LOCK

要求：

MVCC 层不能破坏已有 StorageEngine。

通过 adapter：

MvccStorageEngine

====================================================
五、Timestamp Oracle
====================================================

实现：

TimestampOracle

要求：

单调递增：

T1 < T2 < T3

支持：

nextTimestamp()

nextBatch(n)

必须保证：

并发调用不产生重复 timestamp。

引入：

HybridLogicalClock

包含：

physicalTime

logicalCounter

要求：

系统时间回拨不能导致 timestamp 倒退。

测试：

TimestampOracleTest >=15

覆盖：

- concurrent allocation
- monotonicity
- batch allocation
- clock rollback
- overflow boundary

====================================================
六、Snapshot Read
====================================================

实现：

SnapshotReader

接口：

get(key, readTS)

scan(range, readTS)

规则：

只读取：

commitTS <= readTS

如果：

commitTS > readTS

必须不可见。

删除标记：

DELETE

必须正确隐藏旧版本。

测试：

MvccSnapshotTest >=25

覆盖：

- latest read
- historical read
- delete
- overwrite
- concurrent versions
- snapshot isolation

====================================================
七、 Transaction
====================================================

实现：

Transaction

状态：

ACTIVE

PREWRITING

PREPARED

COMMITTED

ROLLED_BACK

ABORTED

事务包含：

txnId

startTS

commitTS

state

primaryKey

locks

API：

begin()

get()

put()

delete()

commit()

rollback()

====================================================
八、Prewrite
====================================================

采用：

Percolator-style two-phase commit

流程：

BEGIN

↓

Prewrite

↓

Commit

Prewrite：

检查：

write conflict

lock conflict

已有 committed version

成功：

写入：

LockRecord

同时：

写入 provisional mutation

注意：

不能提前对外可见。

====================================================
九、Commit
====================================================

Commit：

1. 验证 primary lock
2. 分配 commitTS
3. 写入 WriteRecord
4. 删除 LockRecord
5. mutation 对 Snapshot Read 可见

规则：

commitTS > startTS

并且：

commitTS 单调递增。

====================================================
十、Rollback
====================================================

支持：

显式 rollback

timeout rollback

leader change rollback

failed prewrite rollback

Rollback 必须：

释放 lock

删除 provisional state

保证：

后续 read 不可看到 aborted transaction。

====================================================
十一、LockManager
====================================================

实现：

LockTable

Lock：

primary

txnId

startTS

ttl

lockType

支持：

acquire

release

resolve

check

并发：

多个 transaction 修改同一个 key：

T1:

startTS=100

PUT A

T2:

startTS=101

PUT A

必须检测冲突。

禁止：

lost update

dirty read

dirty write

====================================================
十二、Conflict Detection
====================================================

实现：

ConflictDetector

至少检测：

Write-Write Conflict

Read-Write Conflict

Lock Conflict

例如：

T1:

startTS=100

PUT A

COMMIT 110

T2:

startTS=105

PUT A

T2 prewrite 必须失败。

异常：

WriteConflictException

LockConflictException

TransactionAbortedException

====================================================
十三、Leader Change / Raft Integration
====================================================

事务不能脱离 Raft。

所有：

Prewrite

Commit

Rollback

必须进入对应 Region 的 Raft group。

要求：

Raft commit 后：

Transaction state 才能对外确认。

Leader change：

未提交 transaction：

future 必须显式失败。

禁止：

旧 leader 返回虚假成功。

必须复用 Phase 15 已修复：

failPendingFromLocked

====================================================
十四、Region Transaction
====================================================

单 Region transaction：

支持完整：

BEGIN

PUT

PUT

COMMIT

跨 Region：

本阶段先实现：

TransactionCoordinator

支持：

多个 Region participant。

第一版采用：

2PC

流程：

Coordinator

↓

Prewrite participants

↓

所有 participant 成功

↓

Commit participants

失败：

Rollback all

要求：

跨 Region transaction 不允许部分提交。

====================================================
十五、Transaction Recovery
====================================================

实现：

TransactionRecoveryManager

启动：

读取：

LockTable

Raft committed transaction records

WAL

恢复：

ACTIVE

PREWRITING

PREPARED

必须判断：

commit

rollback

异常状态必须进入：

ABORTED

或：

COMMITTED

禁止：

重启后出现永久锁。

测试：

TransactionRecoveryTest >=20

覆盖：

- crash during prewrite
- crash before commit
- crash after commit
- leader restart
- follower restart
- snapshot restore

====================================================
十六、MVCC GC
====================================================

实现：

MvccGcManager

引入：

SafePoint

规则：

只删除：

commitTS < safePoint

但必须保留：

当前活跃事务需要的版本。

不能删除：

active snapshot 仍可能读取的版本。

GC：

后台线程。

支持：

manual GC

scheduled GC

指标：

mvcc_versions_total

mvcc_gc_versions

mvcc_gc_bytes

mvcc_safe_point

====================================================
十七、Snapshot 与 MVCC
====================================================

现有：

Raft Snapshot

必须兼容：

MVCC versions

Lock records

Transaction metadata

Snapshot restore 后：

MVCC 数据完整。

禁止：

Snapshot 只恢复最新 value。

必须恢复：

历史版本。

====================================================
十八、Redis Gateway
====================================================

保持 Redis Gateway 兼容。

GET：

默认：

readTS = latest committed timestamp

SET：

自动创建：

single-key transaction

DEL：

自动事务化。

不要求 Redis 用户显式 BEGIN。

保证：

原有 Phase 18 Redis tests 不回归。

====================================================
十九、性能目标
====================================================

新增：

docs/benchmark/phase19-mvcc-report.md

基准：

1. MVCC GET
2. MVCC PUT
3. Historical GET
4. Snapshot Scan
5. Transaction single-region
6. Transaction multi-region
7. Conflict workload
8. GC

目标：

MVCC GET：

> 500K ops/s

Single Region transaction：

> 100K txn/s

Conflict detection：

> 500K ops/s

GC：

> 100MB/s

所有指标必须：

多次运行

报告范围

P50

P95

P99

CPU

Memory

GC

禁止只报告最好的一次结果。

====================================================
二十、测试要求
====================================================

Phase 19 新增：

至少 180 tests。

建议：

Timestamp:
15

MVCC:
30

Snapshot:
25

Transaction:
30

Conflict:
20

Recovery:
20

GC:
20

Cross-region:
15

Raft integration:
15

Benchmark:
10

最终：

Phase 19 total tests >1290

要求：

mvn test

必须：

0 failures。

====================================================
二十一、并发测试
====================================================

必须增加：

ConcurrentTransactionTest

场景：

1000 concurrent transactions

至少：

100 concurrent writers

100 concurrent readers

验证：

- no dirty read
- no lost update
- no dirty write
- no phantom version
- no duplicate commit
- no permanent lock

====================================================
二十二、Chaos
====================================================

增加：

TransactionChaosTest

故障：

leader kill

follower kill

partition

restart

snapshot restore

transaction timeout

必须验证：

已提交事务：

永不丢失。

未提交事务：

不会虚假成功。

失败事务：

不会留下永久 lock。

====================================================
二十三、Observability
====================================================

新增：

INFO TRANSACTION

输出：

active_txn

committed_txn

rollback_txn

conflict_txn

lock_count

safe_point

gc_versions

Prometheus：

txn_begin_total

txn_commit_total

txn_rollback_total

txn_conflict_total

txn_commit_latency

txn_active

txn_lock_count

mvcc_read_qps

mvcc_write_qps

mvcc_gc_qps

====================================================
二十四、Compatibility
====================================================

必须保证：

Phase 1–18：

全部回归。

特别：

Redis GET

Redis SET

Redis DEL

Gateway

Raft

Multi-Raft

Region Split

Region Merge

Migration

Snapshot

Leader Transfer

不得因为 MVCC 引入：

数据丢失

路由错误

Raft regression

====================================================
二十五、Documentation
====================================================

新增：

docs/architecture/mvcc.md

docs/architecture/transaction.md

docs/architecture/consistency.md

docs/review/phase19-mvcc-transaction-review.md

docs/benchmark/phase19-mvcc-report.md

必须解释：

Snapshot Isolation

MVCC

2PC

Conflict Detection

Safe Point

GC

Raft Integration

====================================================
二十六、Git Workflow
====================================================

创建：

feature/phase19-mvcc-transaction

开始前：

checkpoint-before-phase19

提交建议：

docs:
ADR

feat:
timestamp

feat:
mvcc

feat:
transaction

feat:
lock

feat:
recovery

feat:
gc

test:
phase19

perf:
benchmark

docs:
phase19-report

最后：

git merge --no-ff

创建：

checkpoint-after-phase19

要求：

工作树 clean。

====================================================
二十七、严格工程原则
====================================================

1.

不要为了通过测试修改测试语义。

2.

不要隐藏 benchmark 失败。

3.

任何一致性问题必须先修根因。

4.

任何 Raft bug 必须增加 regression test。

5.

不要重写已有 StorageEngine。

6.

不要修改已有 Raft consensus semantics。

7.

所有跨 Region transaction 必须明确 participant 状态。

8.

所有异常 transaction 必须最终：

COMMITTED

或

ROLLED_BACK

禁止：

UNKNOWN 永久状态。

9.

任何性能优化必须：

Before

After

P50

P99

CPU

GC

10.

如果目标未达：

如实报告

定位瓶颈

登记 TD。

====================================================
Phase 19 Definition of Done
====================================================

必须全部满足：

✅ MVCC

✅ Timestamp Oracle

✅ HLC

✅ Snapshot Read

✅ Lock Manager

✅ Conflict Detection

✅ Prewrite

✅ Commit

✅ Rollback

✅ Transaction Recovery

✅ MVCC GC

✅ Raft Integration

✅ Cross-Region 2PC

✅ Snapshot MVCC restore

✅ Redis compatibility

✅ Chaos validation

✅ Prometheus metrics

✅ >1290 total tests

✅ 0 regression

✅ Benchmark report

✅ ADR-0071 ~ ADR-0076

✅ Review report

✅ Git checkpoint

Phase 19 完成后：

Tiering-KV 将正式具备：

Distributed KV

- Multi-Raft
- Region
- MVCC
- Transaction
- Snapshot Isolation
- Distributed 2PC

成为一个真正的：

Mini Distributed Database Kernel。

现在开始执行 Phase 19。
