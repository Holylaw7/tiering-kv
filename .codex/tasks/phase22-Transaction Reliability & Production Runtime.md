# Phase 22 Prompt — Transaction Reliability & Production Runtime

```text
你现在负责继续开发 tiering-kv 项目的 Phase 22。

==================================================

Current Status

Phase 1-21 已完成。

当前能力：

Storage:
- LSM
- WAL
- SSTable
- Snapshot
- MVCC
- Online Compaction


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
- CLUSTER
- MOVED/ASK


Transaction:

- HLC
- Timestamp Oracle
- MVCC Snapshot
- Percolator 2PC
- DistributedTxnRouter
- TxnParticipant
- TransactionMetadataRaft


Production:

- Docker Compose
- TCP RPC
- TLS
- Metrics
- Chaos


Phase21:

commit:

0c1e30e


Tests:

1725/1725


==================================================

Phase22 Goal

目标：

将 Phase21 的分布式事务从：

“协议正确”

升级到：

“生产可靠运行”。

重点：

1.
完整容器运行时事务链路

2.
Transaction Lifecycle Management

3.
Lock Resolver

4.
Storage Failure Recovery

5.
Metadata Transaction Ordering

6.
Production Chaos


==================================================


# Goal 1：Full Runtime Transaction Deployment


解决：

TD-043


当前：

测试：

Gateway
+
Participant

存在进程内 shortcut。


升级：

真实部署：


docker-compose:

```

node1

Gateway
Region-A Leader

node2

Region-B Leader
Participant

node3

Metadata Raft
Replica

```


要求：

所有事务 RPC：

必须：

TCP

真实容器网络


禁止：

LocalTxnTransport


新增：

```

docker-compose.transaction.yml

TransactionGatewayContainer

TransactionParticipantContainer

TxnMetadataContainer

```


测试：

```

EndToEndDistributedTxnTest

Client

|

Gateway container

|

node1

|

node2 participant

SET keyA

SET keyB

COMMIT

restart node2

verify

```



==================================================


# Goal 2：Transaction Lifecycle Management


新增：

TransactionLifecycleManager


负责：


```

ACTIVE

|

PREWRITE

|

COMMITTED

or

ROLLBACK

|

EXPIRED

```


支持：

transaction TTL


配置：

```

txn.ttl.seconds

txn.max-duration

```


要求：

长事务：

自动 abort


新增：

```

TxnTimeoutScheduler

TxnHeartbeatManager

```


支持：

heartbeat:

```

client
|
coordinator
|
participants

```


测试：

```

LongTransactionTimeoutTest

HeartbeatExtensionTest

ExpiredTransactionRecoveryTest

```



==================================================


# Goal 3：Distributed Lock Resolver


当前：

Lock recovery 基础存在。


升级：

TiKV 风格：

发现：

```

primary lock

secondary locks

```


流程：

```

DetectLock

|

CheckPrimary

|

ResolveCommit

or

Rollback

```


新增：

```

LockResolver

TxnStatusCache

```


要求：

解决：

- orphan lock
- coordinator crash
- network timeout


测试：

```

PrimaryCrashResolveTest

SecondaryLockCleanupTest

```



==================================================


# Goal 4：Transaction Metadata Ordering


当前：

Metadata:

local log
+
Raft


问题：

存在：

at-least-once window


升级：


TransactionMetadataState:

```

txnId

state

commitTS

participants

decisionIndex

```


所有状态：

必须：

Raft ordered


流程：


```

COMMIT decision

```
    |
```

Metadata Raft

```
    |
```

Participants commit

```


禁止：

先 participant commit

后 metadata commit


新增：

ADR-0087

Transaction Decision Ordering



测试：

```

MetadataReorderTest

DuplicateCommitTest

CrashBetweenDecisionTest

```



==================================================


# Goal 5：Disk Failure Chaos


关闭：

TD-044


真实注入：


Linux Docker:


disk:

```

slow io

disk full

readonly fs

corrupt wal

```


工具：

fio

mount

tc


测试：

```

DiskSlowDuringCommitTest

DiskFullRecoveryTest

WalCorruptionRecoveryTest

```


验证：

- no committed data loss
- rollback possible
- recovery successful



==================================================


# Goal 6：Transaction Metrics Upgrade


新增：


Prometheus:


Transaction:

```

txn_active

txn_commit_latency

txn_abort_reason

txn_lock_wait_seconds

txn_long_running

txn_expired_total

```


Lock:

```

lock_total

lock_resolve_total

lock_wait_time

```


INFO:


```

INFO TRANSACTION

active:

prepared:

locked_keys:

long_running:

```



==================================================


# ADR


新增：


ADR-0087

Transaction Decision Ordering


ADR-0088

Transaction TTL and Heartbeat


ADR-0089

Distributed Lock Resolver


ADR-0090

Runtime Deployment Architecture



==================================================


# Testing


新增：

目标：

>=220


Runtime:

40


Lifecycle:

35


Lock Resolver:

40


Metadata:

35


Disk Chaos:

35


Metrics:

15


Benchmark:

10


Integration:

10



全量：

Phase1-22

必须：

0 failures



==================================================


# Benchmark Targets


End-to-End Redis Transaction:


SET:

>50K ops/s


GET:

>500K ops/s


Cross Region Transaction:

>50K txn/s



Recovery:


transaction recovery:

<1s


Lock resolve:

<500ms



==================================================


# Documentation


新增：


docs/review/

phase22-transaction-reliability-review.md


docs/testing/

phase22-chaos-report.md


docs/deployment/

phase22-runtime-deployment.md


docs/benchmark/

phase22-report.md



==================================================


# Engineering Rules


必须：

1.
不修改 Raft consensus semantics


2.
Transaction decision must be durable before commit


3.
All RPC idempotent


4.
All failures produce TD


5.
Benchmark truthfully report


6.
Conventional Commits



Checkpoint:


checkpoint-before-phase22

checkpoint-after-phase22



Final merge:


merge: integrate Phase22 transaction reliability


==================================================

```
