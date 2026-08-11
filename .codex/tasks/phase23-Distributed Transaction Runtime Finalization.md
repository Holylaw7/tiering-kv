# Phase 23 Task Prompt — Distributed Transaction Runtime Finalization

## Context

当前系统已经完成：

- Phase 1–18：
  - StorageEngine
  - Raft
  - Multi-Raft
  - Region
  - Gateway
  - Migration
  - Production Metrics

- Phase 19–22：
  - MVCC
  - Percolator 2PC
  - Distributed Transaction RPC
  - Transaction Metadata Raft
  - Lifecycle TTL
  - LockResolver
  - Recovery
  - Disk Failure Semantic Validation

Current baseline：

```
Phase 22

develop:
2b34365 merge: integrate Phase22 transaction reliability

Tests:
1849/1849 PASS

Bench:
Redis SET(transaction)
128K~150K ops/s

Cross Region Txn
33K~59K txn/s

Recovery
0~15ms
```

Phase 22 Remaining Debt:

```
TD-043:
TCP transaction complete
BUT:
container runtime Gateway/Participant orchestration missing


TD-045:
Phase22新增测试数量124 < target220


TD-046:
disk chaos only JVM semantic injection
real container disk fault missing
```

---

# Phase 23 Goal

## Distributed Transaction Runtime Finalization

完成：

1. Transaction Runtime 容器化部署闭环
2. Gateway → Coordinator → Participant → MetadataRaft 全链路真实 TCP
3. Linux Docker 真实磁盘故障注入
4. 补齐测试覆盖
5. 生产运行配置冻结

原则：

- 不修改 Raft 共识算法
- 不修改 MVCC 数据模型
- 不修改 2PC 状态机语义
- 不引入新的事务协议
- 只做 Runtime / Reliability / Productionization

---

# Goal 1 — Transaction Runtime Container Orchestration

## Objective

关闭 TD-043。

实现真实部署：

```
docker network txn-cluster


             redis client

                  |
                  v

        Transaction Gateway


                  |
                  v


        Coordinator Node


          /          \


 Participant-A    Participant-B


          \          /

              |
              v


       TxnMetadataRaft
          (3 nodes)

```

---

## Implementation

新增：

```
runtime/
 ├── TxnRuntimeMain
 ├── GatewayRuntime
 ├── CoordinatorRuntime
 ├── ParticipantRuntime
 └── MetadataRuntime
```

启动参数：

```
--node-id
--role
--region-id
--raft-group
--rpc-port
--metrics-port
```

---

新增：

```
deploy/

docker-compose.transaction.yml

docker/
 ├── Dockerfile.runtime
 ├── Dockerfile.gateway
 └── Dockerfile.participant
```

要求：

- 独立 JVM
- 独立 volume
- 独立 network namespace
- 独立日志目录

---

验证：

测试：

```
ContainerTransactionRuntimeTest
```

覆盖：

- gateway restart
- coordinator restart
- participant restart
- metadata leader restart

---

Acceptance:

```
Gateway
 |
 TCP
 |
Coordinator
 |
 TCP
 |
Participant
 |
Raft
 |
Metadata

全链路无 LocalTransport
```

---

# Goal 2 — Real Disk Chaos Injection

关闭 TD-046。

环境：

Linux Docker。

使用：

```
tc
dmsetup
fallocate
fio
```

---

## Chaos Cases

### Disk Full

模拟：

```
Metadata node disk full
```

验证：

commit:

```
decisionIndex exists

↓

disk failure

↓

restart

↓

recoverFromRaft

↓

participants complete
```

要求：

无：

```
COMMITTED LOST
```

---

### Readonly Disk

流程：

```
mount readonly

↓

commit request

↓

failure

↓

recover

```

---

### Slow Disk

使用：

```
fio latency injection
```

模拟：

```
WAL fsync > election timeout
```

验证：

- no split brain
- no duplicate commit

---

新增：

```
DiskChaosContainerTest

```

---

# Goal 3 — Transaction Lifecycle Persistence

当前：

```
LifecycleManager
      |
      |
 memory state
```

升级：

```
LifecycleManager

        |

TxnLifecycleRecord

        |

MetadataRaft
```

新增：

```
ACTIVE
PREWRITE
HEARTBEAT
EXPIRED
COMMITTED
ROLLBACK
```

持久化：

```
txn_id
start_ts
expire_at
state
decision_index
```

恢复：

```
restart

↓

scan lifecycle

↓

resume heartbeat

↓

abort expired
```

---

ADR:

```
ADR-0091 Transaction Lifecycle Persistence
```

---

# Goal 4 — LockResolver Distributed RPC

当前：

```
Coordinator
 |
Local LockResolver
```

升级：

```
LockResolverClient

        |

Participant RPC

        |

Primary transaction owner

```

新增 RPC:

```
CHECK_TXN_STATUS

RESOLVE_LOCK

HEARTBEAT_LOCK
```

要求：

跨 Region：

```
A region lock

B region read

↓

detect lock

↓

RPC resolve

↓

continue
```

---

ADR:

```
ADR-0092 Distributed Lock Resolver
```

---

# Goal 5 — Complete Phase22 Test Target

关闭 TD-045。

新增测试：

目标：

```
>=100 additional tests
```

重点：

## Runtime

```
30
```

## Disk Chaos

```
30
```

## Lifecycle

```
20
```

## Lock RPC

```
20
```

目标：

```
Phase 23 total tests

>2000
```

---

# Goal 6 — Production Configuration Freeze

新增：

```
docs/deployment/production-runtime.md
```

包含：

## JVM

```
heap
gc
thread
```

## Transaction

```
txn.ttl
txn.max-duration
heartbeat.interval
lock.timeout
```

## Raft

```
election timeout
heartbeat
snapshot
```

## Network

```
rpc timeout
retry
backoff
```

---

# ADR Requirements

必须新增：

```
ADR-0091
Transaction Lifecycle Persistence


ADR-0092
Distributed Lock Resolver


ADR-0093
Production Runtime Deployment


ADR-0094
Disk Chaos Validation
```

要求：

先 ADR

后代码。

---

# Test Plan

新增：

```
>=200 tests
```

分类：

| Module                | Count |
| --------------------- | ----: |
| Container Runtime     |    40 |
| TCP Transaction       |    35 |
| Lifecycle Persistence |    30 |
| Lock Resolver RPC     |    25 |
| Disk Chaos            |    35 |
| Recovery              |    20 |
| Metrics               |    10 |
| Benchmark             |     5 |

---

# Benchmark Target

## Transaction Runtime

目标：

```
Gateway SET(transaction)

>100K ops/s
```

## Cross Node 2PC

目标：

```
>50K txn/s
```

## Recovery

目标：

```
<1s
```

## Disk Failure

目标：

```
zero lost commit
```

---

# Chaos Matrix

必须覆盖：

| Failure              | Expected         |
| -------------------- | ---------------- |
| Gateway kill         | retry            |
| Coordinator kill     | metadata recover |
| Participant kill     | resolve          |
| Metadata leader kill | Raft elect       |
| Disk full            | recover          |
| Readonly disk        | rollback         |
| Slow disk            | no split brain   |
| Network loss         | retry            |

---

# Git Workflow

Branch:

```
feature/phase23-runtime-finalization
```

Commits:

```
docs: add ADR-0091~0094

feat(runtime): transaction container runtime

feat(txn): lifecycle persistence

feat(lock): distributed resolver

test: phase23 reliability suite

docs: phase23 reports
```

Merge:

```
merge: integrate Phase23 transaction runtime finalization
```

Checkpoint:

```
checkpoint-before-phase23

checkpoint-after-phase23
```

---

# Deliverables

必须生成：

```
docs/review/
phase23-runtime-finalization-review.md


docs/testing/
phase23-chaos-report.md


docs/benchmark/
phase23-runtime-report.md


docs/deployment/
production-runtime.md
```

---

# Phase 23 Success Criteria

全部满足：

```
✅ Gateway/Coordinator/Participant 容器化运行

✅ 全链路 TCP，无 LocalTransport

✅ Metadata Raft 决策恢复

✅ 生命周期状态持久化

✅ LockResolver 跨节点

✅ Disk chaos 真实执行

✅ 新增测试 >=200

✅ 全量回归 2000+

✅ 无 committed transaction 丢失

✅ develop 合并成功
```
