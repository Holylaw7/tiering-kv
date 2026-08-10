# Phase 7 Task: Concurrency Optimization Layer

状态：✅ 已完成（2026-08-10）

# 1. Task Identity

Project:

Tiering-KV

Phase:

Phase 7

Module:

Concurrency Optimization

Goal:

优化 KV Engine 并发模型，实现高吞吐、低延迟、多 Key 并行执行能力。

---

# 2. Execution Rules

开始前必须读取：

```

.codex/MASTER_PROMPT.md

.codex/DEVELOPMENT_RULES.md

.codex/AGENT_CONTEXT.md

ROADMAP.md

docs/adr/

docs/design/

git history

```

必须遵循：

```

需求分析

↓

并发模型设计

↓

ADR

↓

接口设计

↓

TDD

↓

实现

↓

Benchmark

↓

Code Review

↓

Git Commit

```

禁止：

- 删除已有锁保证正确性
- 为追求性能破坏一致性
- 修改 WAL 语义
- 修改 SSTable 格式
- 绕过 Storage SPI
- 使用未经验证的 lock-free 算法

---

# 3. Current Architecture

当前：

```

Netty EventLoop

```

    |

```

Command Handler

```

    |

```

StorageEngine

```

    |

```

MemTable

```

    |

```

64 Segment Lock

```

问题：

```

单连接串行

热点 Key 竞争

读路径存在锁

写吞吐受限

```

---

# 4. Target Architecture

升级：

```

```

             Netty


               |


         Command Router


               |


      Key Sharded Executor


               |

```

+--------------------------------+

|                                |

Shard-0                       Shard-N

|                                |

Storage Operations

|

MemTable

|

Optimized Concurrent Structure

```

目标：

```

不同 Key 并行

同 Key 顺序

读低锁

写高吞吐

```

---

# 5. Required ADR

## ADR-0023

文件：

```

docs/adr/ADR-0023-key-sharding-execution-model.md

```

标题：

```

Key Sharded Execution Model

```

比较：

- global executor
- key sharding
- actor model
- lock based

确定最终方案。

---

## ADR-0024

文件：

```

docs/adr/ADR-0024-memtable-concurrency-strategy.md

```

标题：

```

MemTable Concurrency Strategy

```

比较：

- striped lock
- ConcurrentHashMap
- skiplist lock-free
- copy-on-write

确定优化方向。

---

## ADR-0025

文件：

```

docs/adr/ADR-0025-hot-key-mitigation.md

```

标题：

```

Hot Key Mitigation Strategy

```

设计：

- 热点检测
- 请求合并
- 本地缓存
- 限流策略

---

# 6. Key Sharded Executor

新增模块：

```

execution/

├── KeyShardExecutor

├── ShardWorker

├── ShardRouter

├── ShardQueue

└── ExecutionContext

```

---

# 7. Sharding Model

Key:

```

hash(key) % shardCount

```

例如：

```

shard 0

shard 1

...

shard N

```

要求：

同 Key：

```

SET A

GET A

DELETE A

```

必须保持顺序。

不同 Key：

```

SET A

SET B

SET C

```

允许并行。

---

# 8. Executor Thread Model

设计：

```

Netty Thread

```

|

```

submit task

```

|

```

Shard Worker

```

|

```

Storage Engine

```

要求：

用户请求线程：

不能执行：

- Flush
- Migration
- Compaction

---

# 9. MemTable Concurrency Optimization

当前：

```

64 Segment Striped Lock

```

评估：

## Option A

优化 striped lock

增加：

```

64 → 256 segments

```

---

## Option B

Read Mostly Structure

例如：

```

AtomicReference

*

immutable metadata

```

---

## Option C

Concurrent SkipList

评估：

```

ConcurrentSkipListMap

```

---

必须：

生成 ADR 后选择。

---

# 10. Lock Contention Metrics

新增：

```

ConcurrencyMetrics

```

统计：

```

lock wait time

operation latency

queue depth

shard utilization

```

---

# 11. Lock Free Read Path

目标：

GET路径：

当前：

```

GET

|

segment lock

|

skiplist lookup

```

优化：

```

GET

|

immutable snapshot

|

lock-free lookup

```

要求：

保证：

- version
- TTL
- tombstone

语义不变。

---

# 12. Hot Key Detection

新增：

```

concurrency/hotkey/

├── HotKeyDetector

├── AccessCounter

├── HotKeyEntry

└── HotKeyPolicy

```

检测：

```

key access frequency

QPS

time window

```

---

# 13. Hot Key Handling

支持：

## Read Hot Key

策略：

```

local read cache

```

要求：

不能破坏：

```

version consistency

```

---

## Write Hot Key

策略：

```

request serialization

*

backpressure

```

---

# 14. Request Coalescing

实现：

```

same key concurrent GET

```

    |

```

single loader

```

    |

```

share result

```

避免：

```

10000 requests

```

    |

```

10000 storage reads

```

---

# 15. Benchmark Requirements

新增：

```

benchmarks/concurrency/

```

---

## Throughput

测试：

```

GET

SET

mixed workload

```

规模：

```

10 threads

50 threads

100 threads

256 threads

```

指标：

```

ops/s

P50

P95

P99

```

---

## Hot Key Benchmark

场景：

```

90% traffic

1 key

```

观察：

```

latency

throughput

lock contention

```

---

## Sharding Benchmark

比较：

```

single executor

vs

sharded executor

```

---

# 16. Performance Target

目标：

## Throughput

GET:

```

> 1M ops/s

```

SET:

```

> 500K ops/s

```

---

## Latency

P99:

```

<1ms

```

---

## Concurrency

100线程：

```

0 data race

0 lost update

```

---

# 17. Testing Requirements

新增：

```

tests/unit/concurrency/

```

必须包含：

```

ShardRouterTest

ExecutorOrderingTest

ConcurrentReadWriteTest

HotKeyDetectorTest

RequestCoalescingTest

ConcurrencyStressTest

```

---

覆盖：

- 同Key顺序
- 不同Key并行
- 高并发写
- 热点Key
- 数据一致性
- 无死锁

---

# 18. Race Detection

必须执行：

```

stress test

multiple JVM runs

```

检查：

- lost update
- visibility issue
- deadlock

---

# 19. Documentation Update

更新：

README：

增加：

```

Concurrency Architecture

```

ROADMAP：

```

Phase 7 Completed

```

AGENT_CONTEXT：

```

Concurrency:

key sharding enabled

lock optimization completed

```

CHANGELOG：

```

feat(concurrency):

implement high concurrency execution model

```

---

# 20. Git Workflow

开始：

```

git tag checkpoint-before-phase7-concurrency

```

提交：

```

docs:
add concurrency ADR

feat(execution):
implement key shard executor

feat(storage):
optimize memtable concurrency

feat(concurrency):
add hot key detection

test:
add concurrency tests

perf:
add concurrency benchmark

```

---

# 21. Completion Criteria

Phase7完成：

[x] ADR-0023完成

[x] ADR-0024完成

[x] ADR-0025完成

[x] Key Sharded Executor完成

[x] 同Key顺序保证

[x] 不同Key并行执行

[x] MemTable并发优化

[x] 热点Key检测

[x] 请求合并

[x] 压测完成

[x] Race测试完成

[x] Benchmark完成

[x] Phase1-6回归通过

[x] Git merge完成

## 验收结果

- `mvn test`：全量用例全绿（Phase 1–6 全部回归通过）。
- KeyShardExecutor：同键 FIFO、异键并行；ResponseSequencer 保证 RESP 保序。
- MemTable 256 段；热点检测 + 请求合并 + 本地读缓存（写失效）。
- 基准与报告：docs/benchmark/concurrency-report.md。

---

# 22. Final Report Format

输出：

```

# Phase 7 Completion Report

Architecture:

...

Execution Model:

...

Concurrency Strategy:

...

Hot Key Handling:

...

ADR:

...

Tests:

...

Benchmark:

...

Performance:

...

Git:

...

Known Limitations:

...

Next Phase:

Phase 8 IO Optimization

```

---

# Start Phase 7

执行顺序：

1.  读取 Phase1-6 架构

2.  分析当前并发瓶颈

3.  生成 ADR

4.  设计 Key Sharding 模型

5.  实现测试

6.  实现 Executor

7.  优化 MemTable

8.  加入 Hot Key 处理

9.  Benchmark

10. Code Review

11. Git提交

禁止直接编码。

```

```
