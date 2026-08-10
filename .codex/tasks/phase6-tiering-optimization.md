# 1. Task Identity

状态：✅ 已完成（2026-08-10）

Project:

Tiering-KV

Phase:

Phase 6

Module:

Tiering Optimization Layer

Goal:

将冷热分层系统从手动控制升级为自动调度系统，实现生产级 Memory / Disk 协同管理。

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

架构设计

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

- 绕过 StorageEngine
- 修改 SSTable 格式不经过 ADR
- 删除 WAL 保护机制
- 同步阻塞用户线程执行迁移
- 无恢复机制的后台任务
- 直接修改 Phase 1-5 核心语义

---

# 3. Current Architecture

当前：

```

Command

```

|

```

StorageEngine

```

|

```

WAL

```

|

```

MemTable

```

|

```

Manual Flush

```

|

```

SSTable

```

|

```

Compaction

```

存在问题：

```

Flush需要人工触发

Migration同步执行

pending状态不持久化

磁盘慢时无法保护内存

```

---

# 4. Target Architecture

升级：

```

```

             Command


                |


          TieringController


                |


    +-----------+------------+

    |                        |

```

Memory Management        Disk Management

```

    |                        |

```

FlushScheduler          MigrationScheduler

```

    |                        |

```

Immutable MemTable      MigrationQueue

```

    |                        |

```

SSTable Writer          ColdStorage

```

                |


         BackPressureController

```

```

---

# 5. Required ADR

## ADR-0020

文件：

```

docs/adr/ADR-0020-tier-scheduling-model.md

```

标题：

```

Tier Scheduling Model Selection

```

比较：

- synchronous scheduling
- asynchronous worker model
- event driven model

最终确定。

---

## ADR-0021

文件：

```

docs/adr/ADR-0021-memory-watermark-policy.md

```

标题：

```

Memory Watermark Policy

```

定义：

例如：

```

LOW_WATERMARK

70%

HIGH_WATERMARK

85%

CRITICAL

95%

```

说明：

- flush trigger
- write blocking
- recovery

---

## ADR-0022

文件：

```

docs/adr/ADR-0022-migration-persistence.md

```

标题：

```

Pending Migration Persistence Strategy

```

比较：

- WAL extension
- migration manifest
- standalone log

最终方案。

---

# 6. Tiering Controller

新增模块：

```

storage/tiering/

├── TieringController

├── TierState

├── StorageMetrics

├── WatermarkManager

├── FlushScheduler

├── MigrationScheduler

├── BackPressureController

└── TierWorkerPool

```

职责：

统一管理：

- memory pressure
- flush
- migration
- backpressure

---

# 7. Automatic Flush

实现：

```

MemoryManager

```

    |

```

WatermarkManager

```

    |

```

FlushScheduler

```

    |

```

Immutable MemTable

```

    |

```

SSTableWriter

```

要求：

## Trigger

支持：

```

memory bytes > high watermark

or

entry count threshold

or

manual flush

```

---

## Flush线程

要求：

后台执行。

禁止：

```

Client Thread

```

|

```

flush

```

|

```

disk IO

```

---

# 8. Migration Scheduler

当前：

```

Eviction

|

Migration

|

Delete

```

升级：

```

Eviction

|

MigrationQueue

|

Worker Thread

|

ColdStorage

|

Verify

|

Delete Memory

```

---

# 9. Migration Queue Design

新增：

```

MigrationTask

```

包含：

```

key

value

version

source

target

retryCount

status

```

状态：

```

PENDING

RUNNING

SUCCESS

FAILED

RETRY

```

---

# 10. Pending Migration Persistence

解决：

```

pending migration crash loss

```

实现：

```

migration/

migration.log

```

记录：

```

MOVE

key

version

target

status

```

启动恢复：

```

Open migration log

```

    |

```

Recover unfinished tasks

```

    |

```

Resume migration

```

---

# 11. BackPressure Controller

新增：

```

BackPressureController

```

作用：

防止：

```

Disk slower than writes

```

    |

```

Migration backlog increase

```

    |

```

Memory overflow

```

策略：

## NORMAL

正常写。

---

## WARNING

降低写入速度。

---

## CRITICAL

限制新写入。

---

# 12. Storage Metrics

新增：

```

StorageMetrics

```

统计：

Memory:

```

usedBytes

maxBytes

entryCount

```

Migration:

```

pendingTasks

success

failed

latency

```

Flush:

```

flushCount

flushBytes

flushLatency

```

Cold:

```

sstableCount

diskUsage

```

---

# 13. Thread Model

必须设计：

```

Netty EventLoop

```

    |

    |

```

Background Workers

```

    |

```

+---------------+

Flush Worker

Migration Worker

Compaction Worker

```

要求：

用户请求线程不能执行：

- flush
- migration
- compaction

---

# 14. Failure Handling

必须支持：

## Migration失败

例如：

```

disk full

IO error

```

结果：

```

FAILED

retry

keep memory copy

```

---

## Flush失败

要求：

```

immutable memtable

保留

等待重试

```

---

## Worker异常

不能导致：

```

Server shutdown

```

---

# 15. Testing Requirements

新增：

```

tests/unit/tiering/

```

必须包含：

```

WatermarkManagerTest

FlushSchedulerTest

MigrationQueueTest

MigrationRecoveryTest

BackPressureTest

TieringControllerTest

```

覆盖：

- watermark触发
- 自动flush
- 异步migration
- crash recovery
- retry
- backpressure
- worker异常

---

# 16. Integration Tests

新增：

```

TieringIntegrationTest

```

流程：

```

SET大量数据

```

    |

```

Memory超过阈值

```

    |

```

自动flush

```

    |

```

Eviction

```

    |

```

Migration

```

    |

```

Disk存在数据

```

    |

```

Restart

```

    |

```

数据恢复

```

---

# 17. Benchmark Requirements

新增：

```

benchmarks/tiering/

```

测试：

## Automatic Flush

指标：

```

flush latency

throughput

```

---

## Migration Throughput

测试：

```

100K

1M entries

```

指标：

```

migration ops/s

P99 latency

```

---

## Memory Pressure

模拟：

```

continuous writes

memory limit

```

观察：

```

memory usage

backpressure

latency

```

---

# 18. Performance Target

目标：

Flush:

```

background flush

client latency impact <5%

```

Migration:

```

> 50K entries/s

```

Backpressure:

```

memory never exceed limit

```

---

# 19. Documentation Update

更新：

README:

增加：

```

Automatic Tiering Architecture

```

ROADMAP:

```

Phase 6 Completed

```

AGENT_CONTEXT:

```

Current Phase:

Phase 6 completed

Features:

Automatic flush

Migration scheduler

Backpressure

```

CHANGELOG:

```

feat(tiering):

implement automatic storage management

```

---

# 20. Git Workflow

开始：

```

git tag checkpoint-before-phase6-tiering

```

提交：

```

docs:
add tiering ADR

feat(tiering):
implement watermark manager

feat(storage):
add flush scheduler

feat(storage):
add migration scheduler

feat(system):
add backpressure controller

test:
add tiering tests

perf:
add tiering benchmark

```

---

# 21. Completion Criteria

Phase6完成：

[x] TieringController完成

[x] 自动Flush完成

[x] Watermark策略完成

[x] Migration Scheduler完成

[x] Pending Migration恢复完成

[x] Backpressure完成

[x] Metrics完成

[x] Worker模型完成

[x] Integration Test完成

[x] Benchmark完成

[x] ADR-0020完成

[x] ADR-0021完成

[x] ADR-0022完成

[x] Git Merge完成

## 验收结果

- `mvn test`：全量用例全绿（Phase 1–5 全部回归通过）。
- 自动 Flush：水位触发 + 后台执行 + 去重 + 失败保留重试；
- 异步迁移：MigrationLog 持久化 + 启动恢复 + 幂等重放 + 重试上限；
- 背压：CRITICAL 有界等待，超时返回 -ERR（BackpressureException）；
- 基准与报告：docs/benchmark/tiering-report.md。

---

# 22. Final Report Format

完成后输出：

```

# Phase 6 Completion Report

Architecture:

...

Scheduling Model:

...

Flush:

...

Migration:

...

Backpressure:

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

Phase 7 Concurrency Optimization

```

---

# Start Phase 6

执行顺序：

1.  读取 Phase1-5 文档

2.  分析自动调度需求

3.  生成 ADR

4.  设计 Scheduler

5.  编写测试

6.  实现 Flush Scheduler

7.  实现 Migration Scheduler

8.  实现 Backpressure

9.  Benchmark

10. Code Review

11. Git提交

禁止直接编码。

```

```
