你现在负责执行 Tiering-KV Phase 15：

Distributed Production Validation & Performance Closure

目标：

将 Tiering-KV 从“生产加固完成”
推进到“真实分布式生产验证阶段”。

Phase 15 不引入新的存储模型，
不修改：

- MemTable 数据模型
- WAL 一致性语义
- SSTable 格式
- Raft Consensus Protocol
- Slot Routing 模型

所有优化必须：

1. 保持已有一致性保证
2. 保持故障恢复能力
3. 使用 ADR 记录
4. Benchmark 数据证明
5. 未达标必须如实记录

==================================================
Phase 14 当前基线
==================================================

已完成：

Storage:

- MemTable.applyBatch
- WAL appendBatch

Flush:

- AdaptiveFlushController

Replication:

- ReplicationController
- putAsync

Security:

- HMAC-SHA256
- nonce 防重放
- mTLS

Metadata:

- MetadataRaftGroup
- FileRaftLog
- Snapshot

Failure:

- 延迟
- 断连
- 丢包
- leader crash
- log corruption

当前测试：

552 / 552

==================================================
Phase 15 目标
==================================================

TD-030

迁移性能：

100B:

18~20MB/s

瓶颈：

snapshot iterator merge

目标：

> 100MB/s

---

TD-031

Raft：

37~68K ops/s

瓶颈：

同步等待

目标：

稳定：

> 100K ops/s

---

TD-035

真实跨机器：

当前：

进程模拟

目标：

真实节点部署 + chaos testing

==================================================
Part 1
Streaming Slot Migration
==================================================

目标：

替换 snapshot migration。

新增模块：

migration.streaming

结构：

SlotMigrationManager

        |

StreamingMigrator

        |

MigrationScanner

        |

BatchEncoder

        |

MigrationSender

设计：

禁止：

一次性 snapshot 全量复制。

改为：

Iterator Streaming:

source slot

↓

scan batch

↓

encode

↓

send

↓

verify

↓

cursor checkpoint

---

新增：

MigrationStreamCursor

字段：

slotId

lastKey

lastVersion

offset

checksum

要求：

支持：

pause

resume

recover

---

Batch Strategy

动态 batch：

256

512

1024

2048

4096

根据：

- entry size
- network RTT
- target lag

自动调整。

---

一致性要求：

迁移期间：

source:

continue write

必须支持：

snapshot version barrier

保证：

不会丢：

migration start 前数据

不会覆盖：

newer version

新增 ADR：

ADR-0053-streaming-migration-design.md

内容：

- streaming rationale
- consistency barrier
- cursor recovery
- batch tuning

测试：

新增 >=20

覆盖：

- streaming copy
- pause/resume
- crash recovery
- cursor corruption
- version conflict

Benchmark：

新增：

StreamingMigrationBenchmark

测试：

100B

1KB

10KB

目标：

100B:

> 100MB/s

1KB:

> 300MB/s

==================================================
Part 2
Fully Async Raft Proposal Pipeline
==================================================

目标：

消除同步等待。

当前：

client

↓

propose

↓

wait future

↓

return

改成：

client

↓

AsyncProposalQueue

↓

BatchCollector

↓

Raft Replication

↓

Callback

---

新增：

AsyncProposalContext

字段：

requestId

term

deadline

callback

---

要求：

禁止：

Future.get()

阻塞等待

支持：

1.

批量 proposal

例如：

1000 requests

↓

single AppendEntries

2.

leader change

旧 leader：

fail callback

client:

自动 retry

3.

Backpressure

Queue:

NORMAL

WARNING

CRITICAL

禁止无限增长。

新增：

AsyncReplicationClient

新增 ADR：

ADR-0054-async-proposal-pipeline.md

测试：

> =20

覆盖：

- async submit
- batch commit
- timeout
- leader change
- retry
- backpressure

Benchmark：

目标：

single shard:

> 100K ops/s

64 writers:

> 200K ops/s

P99:

<10ms

==================================================
Part 3
Certificate Lifecycle Automation
==================================================

目标：

完成 mTLS 生产生命周期。

新增：

CertificateManager

功能：

1.

load

2.

validate

3.

expire detection

4.

reload

5.

rotation

支持：

old certificate

        |

validate new

        |

atomic switch

不中断连接。

新增：

CertificateWatcher

监听：

filesystem

新增 ADR：

ADR-0055-certificate-lifecycle.md

测试：

> =15

覆盖：

- expired cert
- invalid CA
- rotation
- reconnect
- mutual TLS

==================================================
Part 4
Real Cross Machine Deployment
==================================================

部署：

node1:

metadata leader

shard leader

node2:

replica

node3:

replica

要求：

真实：

TCP

TLS

独立 JVM

独立数据目录

---

新增：

Chaos Test

工具：

tc netem

场景：

1.

latency:

100ms

2.

packet loss:

5%

10%

3.

network partition

4.

disk slow

5.

leader kill

验证：

- no data loss
- leader election
- recovery
- replica catchup

新增：

docs/testing/
phase15-chaos-report.md

测试：

> =15

==================================================
Part 5
Observability
==================================================

新增 metrics:

Raft:

raft_proposal_qps

raft_commit_latency

raft_replication_lag

Migration:

migration_speed

migration_cursor

migration_remaining

Security:

certificate_expire_time

新增：

INFO CLUSTER

输出：

node

role

term

leader

slot

新增 ADR：

ADR-0056-cluster-observability.md

==================================================
Testing Requirements
==================================================

新增：

Migration:

> =20

Async Raft:

> =20

Security:

> =15

Chaos:

> =15

Observability:

> =10

新增总测试：

> =80

要求：

mvn test

结果：

Phase 1-15

全部通过

==================================================
Benchmark
==================================================

生成：

docs/benchmark/
phase15-production-validation-report.md

必须比较：

Phase14

vs

Phase15

指标：

Migration:

100B

1KB

10KB

Raft:

1 writer

64 writer

256 writer

Chaos:

recovery time

TLS:

rotation latency

==================================================
Git
==================================================

提交：

Conventional Commit

顺序：

docs ADR

feat streaming migration

feat async raft

feat certificate lifecycle

feat chaos testing

test

benchmark

最终：

merge --no-ff

commit:

merge: integrate Phase 15 production validation

创建：

checkpoint-before-phase15

checkpoint-after-phase15

==================================================
最终输出
==================================================

生成：

Phase 15 Completion Report

格式：

1 Architecture

2 ADR

3 Implementation

4 Tests

5 Benchmark

6 Chaos Result

7 Limitations

8 Next Phase

禁止：

夸大指标

隐藏失败项

必须保留：

真实瓶颈分析

开始执行 Phase 15。
