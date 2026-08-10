# Phase 9 Task: Production Benchmark & Capacity Model

状态：✅ 已完成（2026-08-10）

# 1. Task Identity

Project:

Tiering-KV

Phase:

Phase 9

Module:

Production Benchmark

Goal:

建立 Tiering-KV 可信生产容量模型，完成从单模块性能测试到完整生产链路的性能验证。

---

# 2. Phase Objective

Phase 1-8 已完成：

- RESP协议
- Command Engine
- Memory Engine
- LFU/ARC
- WAL
- SSTable
- Compaction
- Tiering Scheduler
- Key Sharding
- mmap IO
- Block Cache
- OffHeap Memory

Phase 9 目标：

回答以下问题：

```

单机最大QPS是多少？

P99延迟是多少？

瓶颈在哪里？

WAL成本是多少？

网络成本是多少？

冷热比例影响？

迁移能力？

内存容量？

CPU利用率？

```

最终输出：

```

Production Capacity Model

```

---

# 3. Execution Rules

开始前必须读取：

```

.codex/MASTER_PROMPT.md

.codex/DEVELOPMENT_RULES.md

.codex/AGENT_CONTEXT.md

ROADMAP.md

docs/adr/

docs/benchmark/

Phase1-8 Completion Reports

git log

```

必须遵循：

```

Benchmark Design

↓

ADR

↓

Environment Preparation

↓

Baseline Test

↓

Production Test

↓

Analysis

↓

Optimization Recommendation

↓

Report

↓

Git Commit

```

禁止：

- 修改代码迎合Benchmark
- 删除异常数据
- 隐藏瓶颈
- 使用单次测试作为结论
- 不记录测试环境

---

# 4. Required ADR

## ADR-0029

文件：

```

docs/adr/ADR-0029-production-benchmark-methodology.md

```

标题：

```

Production Benchmark Methodology

```

必须定义：

## JVM

记录：

```

Java Version

GC

Heap Size

Direct Memory

JVM Options

```

## Hardware

记录：

```

CPU

Memory

Disk

OS

Filesystem

```

## Dataset

定义：

```

Key数量

Value大小

冷热比例

访问分布

```

## Metrics

统一：

```

Throughput

P50

P95

P99

P999

CPU

Memory

GC

IO

```

---

# ADR-0030

文件：

```

docs/adr/ADR-0030-capacity-model.md

```

标题：

```

Storage Capacity Model

```

建立：

```

QPS

|

CPU

|

Memory

|

Disk

|

Network

```

分析：

- 单节点容量
- 扩展瓶颈
- 成本模型

---

# ADR-0031

文件：

```

docs/adr/ADR-0031-production-deployment-profile.md

```

标题：

```

Production Deployment Profile

```

定义：

```

推荐CPU

推荐Memory

JVM参数

线程数量

WAL策略

Cache大小

Watermark配置

```

---

# 5. Benchmark Architecture

建立三级测试体系：

```

```

          Benchmark Level


                |

    +-----------+-----------+

    |                       |

A B

```

Memory Benchmark          Server Benchmark

```

                            |

                            C

                 Production Full Chain

```

```

---

# 6. Level A: Memory Engine Benchmark

目标：

验证：

```

ShardExecutor

*

MemTable

*

Cache

```

绕过：

```

Network

WAL

Disk

```

---

## Test Cases

### GET

线程：

```

10

50

100

256

```

记录：

```

ops/s

P50

P95

P99

```

---

### SET

测试：

```

single key

random key

hot key

```

---

### Mixed Workload

比例：

```

GET 80%

SET 20%

```

---

# 7. Level B: Server Benchmark

完整：

```

Client

|

RESP

|

Netty

|

CommandEngine

|

ShardExecutor

|

Memory Engine

```

---

工具：

可实现：

```

custom benchmark client

redis-benchmark compatible client

```

---

测试参数：

Connections:

```

50

100

500

```

Pipeline:

```

1

16

64

128

```

---

指标：

```

QPS

Latency

Connection Cost

CPU

```

---

# 8. Level C: Production Full Chain

最终测试：

```

Client

|

Netty

|

CommandEngine

|

ShardExecutor

|

WAL

|

MemTable

|

Flush

|

SSTable

|

BlockCache

|

Migration

```

---

# 9. Production Workloads

必须包含：

## Workload A

纯缓存：

```

90% GET

10% SET

```

---

## Workload B

普通KV：

```

70% GET

30% SET

```

---

## Workload C

热点：

```

90% traffic

10 hot keys

```

---

## Workload D

冷热迁移：

```

Memory pressure

Flush

Migration

Compaction

```

---

# 10. Dataset

至少：

## Small

```

100K keys

```

## Medium

```

1M keys

```

## Large

```

10M keys

```

记录：

```

memory usage

sstable size

cache hit

```

---

# 11. JVM Profiling

必须采集：

## JFR

记录：

```

CPU

Allocation

GC

Lock

Thread

```

---

## GC

记录：

```

Young GC

Full GC

Pause Time

Allocation Rate

```

---

# 12. IO Profiling

记录：

```

WAL write latency

Flush throughput

Compaction throughput

SSTable read latency

Cache hit rate

```

---

# 13. Capacity Model

生成：

```

docs/benchmark/capacity-model.md

```

内容：

## CPU Model

```

QPS/core

```

## Memory Model

计算：

```

Memory

=

MemTable

*

Cache

*

Metadata

*

```

## Storage Model

计算：

```

WAL size

SSTable size

Compaction overhead

```

---

# 14. Performance Target

目标：

## Memory Path

GET:

```

> 3M ops/s

```

SET:

```

> 1M ops/s

```

---

## Server Path

Pipeline 64:

```

> 500K ops/s

```

---

## Full Chain

目标：

```

稳定吞吐模型

P99 <5ms

```

---

# 15. Production Topology

建立：

```

Client Generator

```

    |

```

Tiering-KV Server

```

    |

```

Redis Protocol

```

    |

```

Storage Components

```

要求：

生产参数：

```

独立JVM

独立日志

独立数据目录

独立Benchmark客户端

```

---

# 16. Benchmark Reports

生成：

```

docs/benchmark/

├── phase9-memory-report.md

├── phase9-server-report.md

├── phase9-production-report.md

├── capacity-model.md

└── deployment-profile.md

```

---

# 17. Regression

必须：

```

mvn test

```

结果：

```

Phase1-8

全部通过

```

---

# 18. Code Changes

允许：

仅限：

```

benchmark/

metrics/

profiling/

configuration/

```

原则：

不要为了测试修改核心逻辑。

---

# 19. Git Workflow

开始：

```

git tag checkpoint-before-phase9-benchmark

```

提交：

```

docs:
add benchmark methodology ADR

benchmark:
add memory benchmark

benchmark:
add server benchmark

benchmark:
add production benchmark

docs:
add capacity model

docs:
add deployment profile

test:
benchmark regression

```

最终：

```

--no-ff merge

```

---

# 20. Completion Criteria

Phase9完成：

[x] ADR-0029完成

[x] ADR-0030完成

[x] ADR-0031完成

[x] A级Benchmark完成

[x] B级Benchmark完成

[x] C级Benchmark完成

[x] JVM Profiling完成

[x] IO Profiling完成

[x] Capacity Model完成

[x] Deployment Profile完成

[x] Phase1-8回归通过

[x] Git merge完成

## 验收结果

- 三级基准（A/B/C）与管道 RESP 客户端已实现；
- 容量模型与部署画像（docs/benchmark/capacity-model.md、
  deployment-profile.md）+ 三份阶段报告；
- `mvn test` 全量回归通过。

---

# 21. Final Report Format

输出：

```

# Phase 9 Completion Report

Environment:

Hardware:

JVM:

Architecture:

Benchmark Methodology:

Level A Result:

Level B Result:

Level C Result:

Capacity Model:

Bottleneck Analysis:

Optimization Recommendation:

ADR:

Tests:

Git:

Known Limitations:

Next Phase:
Phase 10 Advanced Optimization

```

---

# Start Phase 9

执行顺序：

1.  读取Phase1-8设计

2.  冻结Benchmark环境

3.  生成Benchmark ADR

4.  建立测试工具

5.  执行A级测试

6.  执行B级测试

7.  执行C级生产测试

8.  采集JFR/GC/IO数据

9.  生成容量模型

10. 生成生产部署建议

11. Code Review

12. Git提交

禁止跳过测试设计直接压测。

```

```
