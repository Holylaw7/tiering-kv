# Phase 20 Prompt — Transaction Productionization & Storage Optimization

```
你现在负责继续开发 tiering-kv 项目的 Phase 20。

项目当前状态：

Phase 1-18：
- LSM Storage Engine
- WAL / SSTable / Snapshot
- Raft
- Multi-Raft
- Region
- Split / Merge
- Migration
- Redis Cluster Gateway
- Production Deployment

Phase 19 已完成：
- HybridLogicalClock
- TimestampOracle
- MVCC Storage Engine
- Snapshot Read
- Percolator 2PC Transaction
- LockTable
- Conflict Detection
- Transaction Recovery
- MVCC GC
- Cross Region Transaction
- Transaction Metrics

当前版本：
develop
Phase 19 commit:
cfa7863

测试基线：
1339/1339 全绿

Phase 20 目标：

将事务 KV 从“功能完整”提升到“生产可用”。

核心方向：

1. MVCC GC 性能优化
2. Redis Gateway 事务语义接入
3. 跨机生产验证
4. MVCC 索引持久化增强
5. Transaction Observability 完善


==================================================
Phase 20 Goals
==================================================


## Goal 1：MVCC GC Production Optimization

解决 TD-041：

当前：

MvccGcManager：

- SafePoint 保留机制
- 单 key 删除旧版本
- 19-29 MB/s

目标：

>100 MB/s


要求：

新增：

package:

```

mvcc/gc

```


实现：


### BatchGcExecutor

支持：

- batch key scan
- batch version delete
- parallel worker


配置：

```

gc.batch.size
gc.worker.count
gc.max.memory

```


流程：

```

SafePoint
|
Version Scanner
|
Batch Collector
|
Delete Planner
|
Parallel Executor
|
StorageEngine Batch Delete

```


禁止：

- 一次删除整个 key
- 阻塞写路径


必须保证：

- 活跃事务版本不可删除
- SnapshotReader 不受影响
- rollback pointer 正确


新增测试：

```

MvccGcPerformanceTest
MvccGcConcurrencyTest
MvccGcSnapshotSafetyTest

```


Benchmark：

目标：

```

GC throughput >100MB/s

```



==================================================


## Goal 2：Redis Gateway Transaction Integration

解决 TD-042。


目标：

Redis 用户无感访问事务 KV。


设计：

Gateway 自动包装单 key 操作。


例如：

SET:

```

SET user:name tom

↓

BEGIN
|
Prewrite
|
Commit
|
END

```


GET:

```

GET user:name

↓

readTS = HLC.now()

SnapshotRead(readTS)

```


DEL：

```

transaction delete

```



新增：

```

TransactionCommandHandler
AutoTransactionExecutor

```


支持：

```

GET
SET
DEL
MGET
MSET

```


要求：

- Redis RESP 协议不变
- 用户无需 BEGIN
- 单 key 强一致


测试：

```

RedisMvccIntegrationTest

SET -> GET
SET -> DELETE
Concurrent Write Conflict
Leader Failover During SET

```



==================================================


## Goal 3：MVCC Index Persistence


当前：

MVCC index:

memory only


升级：

```

MvccIndex
|
Snapshot
|
PersistentMvccIndex

```


要求：

支持：

- restart recovery
- snapshot restore
- incremental rebuild


新增：

```

MvccIndexWriter
MvccIndexReader
MvccIndexSnapshot

```


格式：

```

MAGIC
VERSION
USER_KEY
START_TS
COMMIT_TS
TYPE
CRC32

```


恢复：

```

load snapshot

- replay WAL
- rebuild index

```



==================================================


## Goal 4：Transaction Journal Raft Persistence


当前：

TxnJournal:

```

InMemory
Raft optional

```


升级：

所有事务状态必须进入 Raft。


状态：

```

PREWRITE
COMMIT
ROLLBACK

```


流程：

```

Coordinator

prepare
|
Raft propose txn state
|
participants commit
|
Raft commit

```


保证：

leader crash:

- transaction recovery
- no phantom commit
- no lost commit



新增：

```

PersistentTxnJournal
TxnRecoveryReplay

```


测试：

```

TxnLeaderCrashTest
TxnReplayTest

```



==================================================


## Goal 5：Cross Machine Validation


执行：

Linux + Docker


环境：

```

3 Node Cluster

node1
node2
node3

Region:
r1
r2
r3

Raft:
multi group

Gateway:
Redis TCP

```



加入：

tc netem:


测试：

```

delay 100ms

loss 5%

partition

kill -9

disk slow

```


验证：

- MVCC transaction consistency
- 2PC recovery
- leader transfer
- migration


输出：

```

docs/testing/phase20-chaos-report.md

```



==================================================


## Goal 6：Transaction Observability


新增：

Prometheus:

transaction:

```

txn_begin_total

txn_commit_total

txn_abort_total

txn_conflict_total

txn_latency

txn_recovery_total

```


MVCC:

```

mvcc_versions_total

mvcc_gc_deleted_versions

mvcc_safe_point

```


Gateway:

```

redis_txn_latency

```


INFO:

新增：

```

INFO TRANSACTION

INFO MVCC

```



==================================================


# ADR Requirements


必须新增：

ADR-0078

MVCC Batch GC Design


ADR-0079

Redis Auto Transaction Model


ADR-0080

Persistent MVCC Index


ADR-0081

Transaction Journal Raft Persistence


ADR-0082

Cross Machine Transaction Validation



==================================================


# Testing Requirements


新增测试：

目标：

>=180


分类：

GC：

```

30

```


Gateway Transaction：

```

35

```


MVCC Persistence：

```

25

```


Txn Journal:

```

30

```


Chaos:

```

30

```


Observability:

```

15

```


Benchmark:

```

10

```


Integration:

```

10

```



全量：

Phase 1-20:

必须：

0 failures



==================================================


# Benchmark Targets


## MVCC GC

目标：

```

> 100 MB/s

```


## Redis Gateway

目标：

GET:

```

> 500K ops/s

```


SET:

```

> 100K ops/s

```


## Transaction


Single Region:

```

> 200K txn/s

```


Cross Region:

```

> 50K txn/s

```


## Recovery


transaction recovery:

```

<1s

```



==================================================


# Documentation


新增：

```

docs/review/phase20-transaction-production-review.md

docs/benchmark/phase20-report.md

docs/testing/phase20-chaos-report.md

```


==================================================


# Engineering Rules


必须遵守：

1. 不修改 Raft 核心协议
2. 不破坏 Phase1-19 API
3. 所有事务状态可恢复
4. 所有 benchmark 如实记录
5. 所有发现的问题必须形成 TD 编号
6. 使用 Conventional Commits
7. 完成后：

checkpoint:

```

checkpoint-before-phase20
checkpoint-after-phase20

```


最终提交：

```

merge: integrate Phase 20 transaction productionization

```


==================================================


```
