# Phase 21 Prompt

```text
你现在负责继续开发 tiering-kv 项目的 Phase 21。

当前状态：

Phase 1-20 已完成。

当前能力：

Storage:
- LSM
- WAL
- SSTable
- Snapshot

Consensus:
- Raft
- Multi-Raft
- Region

Cluster:
- Split
- Merge
- Migration
- Placement

Gateway:
- Redis RESP
- CLUSTER SLOTS
- MOVED/ASK

Transaction:
- MVCC
- HLC
- Percolator 2PC
- TransactionRecovery
- PersistentTxnJournal

Phase20:

commit:
95b9abf

Tests:
1523/1523 全绿


==================================================

Phase 21 Goal

目标：

实现真正跨节点分布式事务。

重点：

1. Distributed Transaction Router
2. Cross Region Network 2PC
3. Transaction Metadata Service
4. Real Docker Chaos
5. MVCC Online Compression
6. Production Transaction Observability


==================================================


## Goal 1：Distributed Transaction Router


当前问题：

Gateway:

```

Gateway
|
Local TransactionCoordinator

```


限制：

只能进程内事务。


升级：

新增：

```

DistributedTransactionRouter

```


架构：

```

Redis Gateway

```
  |
  v
```

TxnRouter

```
  |
```

---

| | |
Region1 Region2 Region3

```
  |
```

TransactionParticipant

```


要求：

支持：

- 单 Region
- 多 Region
- 多节点


新增：

```

transaction/router

DistributedTxnRouter

RegionTxnClient

TxnParticipantClient

```


协议：

基于现有 RpcTransport。


支持：

```

PREWRITE
COMMIT
ROLLBACK
HEARTBEAT

```


测试：

```

CrossNodeTransactionTest

Node1 Gateway

Node2 Region Leader

Node3 Replica

SET key1
SET key2

commit

leader kill

recover

```



==================================================


## Goal 2：Network Based 2PC


当前：

TransactionCoordinator:

内存调用。


升级：

Percolator 网络模型。


流程：


Coordinator:

```

Begin

|

Timestamp

|

Prewrite RPC

|

Participants

|

Commit RPC

|

Ack

```


要求：

每个 Region：

独立：

```

TransactionParticipant

```


状态：

```

LOCKED

PREPARED

COMMITTED

ROLLED_BACK

```


必须保证：

leader crash:

- no lost commit
- no phantom commit
- no permanent lock



新增：

ADR-0083

Distributed Transaction Protocol



测试：

```

TxnNetworkFailureTest

participant timeout

leader transfer

network loss

retry

```



==================================================


## Goal 3：Transaction Metadata Service


当前：

TxnJournal:

绑定 Region。


升级：

全局事务状态。


新增：

```

TransactionMetadataService

```


负责：

```

txnId

primary key

participants

state

commitTS

```


存储：

Raft group。


结构：

```

txn_meta_region

```
 |
 Raft

 |
```

TransactionMetadataState

```


恢复：

```

Coordinator crash

restart

load metadata

continue commit/rollback

```


新增：

ADR-0084

Transaction Metadata Raft



测试：

```

CoordinatorCrashRecoveryTest

MetadataLeaderFailoverTest

```



==================================================


## Goal 4：Real Cross Machine Chaos


Phase20:

transport chaos


Phase21:

真实环境。


环境：

Linux

Docker Compose


Topology:

```

node1

Gateway
Region Leader

node2

Region Replica

node3

Metadata + Replica

```


加入：

tc netem:


测试：


Network:

```

delay 100ms

loss 5%

duplicate

partition

```


Storage:

```

disk slow

disk full

```


Process:

```

kill -9

restart

```


验证：

- transaction consistency
- MVCC snapshot
- recovery
- leader election


输出：

```

docs/testing/phase21-real-chaos-report.md

```



==================================================


## Goal 5：MVCC Online Compression


当前：

MVCC Index:

snapshot rebuild。


升级：

在线压缩。


新增：

```

MvccCompactor

```


能力：

- merge versions
- retain SafePoint
- background execution


策略：

```

old versions

```
    |
    v
```

Compaction

```
    |
    v
```

new MVCC file

```


要求：

不阻塞：

- read
- write
- transaction



测试：

```

MvccCompactionTest

ConcurrentReadWriteCompactionTest

```



==================================================


## Goal 6：Transaction Observability


新增：


Prometheus:


```

txn_prepare_latency

txn_commit_latency

txn_rollback_total

txn_network_retry

txn_lock_wait

txn_region_count

txn_recovery_time

```


INFO:

```

INFO TRANSACTION

INFO TXN PARTICIPANTS

```



==================================================


# ADR


新增：


ADR-0083

Distributed Transaction Protocol


ADR-0084

Transaction Metadata Raft


ADR-0085

Online MVCC Compression


ADR-0086

Cross Machine Chaos Validation



==================================================


# Tests


新增：

目标：

>=200


分类：


Distributed Router:

40


Network 2PC:

45


Metadata:

35


Chaos:

40


MVCC Compression:

25


Metrics:

15



全量：

Phase1-21:

必须

0 failures



==================================================


# Benchmark Targets


Cross Node Transaction:

single region:

>100K txn/s


cross region:

>50K txn/s


Recovery:

transaction recovery

<1s


Chaos:

leader recovery

<5s



==================================================


# Documentation


新增：


docs/review/

phase21-distributed-transaction-review.md


docs/benchmark/

phase21-report.md


docs/testing/

phase21-real-chaos-report.md



==================================================


# Engineering Rules


必须：

1.
不修改 Raft consensus semantics


2.
Transaction state must survive crash


3.
All distributed calls idempotent


4.
No hidden benchmark assumptions


5.
All defects create TD


6.
Conventional Commits


checkpoint:

checkpoint-before-phase21

checkpoint-after-phase21


merge:

merge: integrate Phase 21 distributed transaction networking


==================================================

```
