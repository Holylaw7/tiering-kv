# Phase 2 Task: Memory KV Core Engine

## Task Identity

Project:

Tiering-KV

Phase:

Phase 2

Module:

Memory Storage Engine

Goal:

实现高性能内存 KV 存储层（Memory Tier），替换 Phase 1 的 InMemoryKVStore。

建立未来冷热分层、淘汰策略、持久化层所需的存储抽象。

---

# 1. Execution Rules

开始任务前必须读取：

```

.codex/MASTER_PROMPT.md

.codex/DEVELOPMENT_RULES.md

.codex/AGENT_CONTEXT.md

ROADMAP.md

最近 Git Log

```

严格遵守：

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

测试

↓

Benchmark

↓

Git Commit

```

禁止：

- 直接删除 InMemoryKVStore
- 跳过接口设计
- 未生成 ADR 修改核心存储结构
- 使用巨大单体 Class
- 使用全局锁解决所有并发问题
- 删除失败测试

---

# 2. Phase Objective

本阶段完成：

```

Memory Tier

```

架构：

```

Command Layer

```

    |

    |

```

Storage Interface

```

    |

    |

```

MemTable Engine

```

    |

    |

```

Concurrent Memory Structure

```

最终：

Phase 1:

```

Command
|
InMemoryKVStore

```

替换为：

```

Command

|

StorageEngine

|

MemTable

```

---

# 3. Functional Requirements

## 3.1 Basic KV Operations

必须支持：

### PUT

```

SET key value

```

要求：

- 插入新数据
- 覆盖旧数据
- 更新时间

---

### GET

```

GET key

```

要求：

- O(logN) 或接近 O(1)
- 返回最新版本数据

---

### DELETE

支持：

```

DEL key

```

要求：

不要立即物理删除。

采用：

Tombstone 标记。

原因：

未来支持：

- WAL
- Snapshot
- LSM Flush

---

# 4. Storage Interface Design

必须抽象：

```

src/main/storage/

```

设计：

```

StorageEngine

├── put()

├── get()

├── delete()

├── exists()

├── iterator()

└── size()

```

要求：

Command 层不能依赖具体实现。

例如：

禁止：

```java
new MemTable()
```

必须：

```java
StorageEngine storage;
```

---

# 5. MemTable Design

实现：

```
storage/memory/
```

推荐结构：

```
memory/


├── MemTable

├── SkipList

├── Entry

├── Version

├── Iterator

├── MemoryManager

└── TTLManager

```

---

# 6. Data Structure Decision

必须分析：

方案：

## Option A

ConcurrentHashMap

优点：

简单

缺点：

- 无序
- 无range scan
- 不利于未来flush

---

## Option B

SkipList

参考：

LevelDB MemTable

优点：

- 有序
- O(logN)
- 支持iterator
- 支持未来SSTable生成

---

## Option C

Tree Structure

要求：

ADR比较。

最终选择必须记录：

ADR。

---

# 7. ADR Requirements

必须生成：

## ADR-0007

文件：

```
docs/adr/ADR-0007-memtable-data-structure.md
```

标题：

```
MemTable Data Structure Selection
```

内容：

比较：

- HashMap
- SkipList
- Tree

说明：

最终选择及原因。

---

## ADR-0008

文件：

```
docs/adr/ADR-0008-memory-concurrency-model.md
```

标题：

```
Memory Concurrency Model
```

必须比较：

Global Lock

RWLock

Striped Lock

Lock Free

记录：

最终方案。

---

## ADR-0009

文件：

```
docs/adr/ADR-0009-ttl-management-strategy.md
```

标题：

```
TTL Management Strategy
```

比较：

Lazy Expiration

Active Expiration

Hybrid Expiration

---

# 8. Entry Data Model

设计：

```
KeyValueEntry
```

至少包含：

```
key

value

createTimestamp

updateTimestamp

expireTimestamp

version

deleted

size
```

要求：

支持未来：

- WAL
- Snapshot
- LSM Flush
- Compaction

---

# 9. TTL Design

实现：

```
TTLManager
```

支持：

SET:

```
SET key value EX seconds
```

或者：

内部API：

```
put(key,value,ttl)
```

必须支持：

## Lazy Expiration

GET时检查：

```
currentTime > expireTime
```

---

## Active Expiration

后台线程：

周期扫描。

要求：

不能阻塞主线程。

---

# 10. Memory Management

实现：

```
MemoryManager
```

负责：

统计：

```
usedMemory

maxMemory
```

支持：

配置：

```
max-memory
```

超过限制：

触发：

```
eviction callback
```

注意：

本阶段不实现LFU/ARC。

只提供接口。

---

# 11. Concurrency Requirements

禁止：

整个MemTable:

```java
synchronized
```

要求：

至少实现：

Striped Lock

例如：

```
Segment 0

Segment 1

Segment 2

...

Segment N

```

不同key进入不同segment。

目标：

降低锁竞争。

---

# 12. Testing Requirements

必须先写测试。

目录：

```
tests/unit/storage/
```

必须包含：

```
MemTableTest


StorageEngineTest


DeleteTest


TTLTest


IteratorTest


ConcurrentAccessTest


MemoryManagerTest
```

覆盖：

## Functional

- PUT
- GET
- DELETE
- EXISTS

## Boundary

- empty key
- empty value
- large value

## TTL

- expire
- no expire

## Concurrency

至少：

100线程

10000操作

---

# 13. Integration Requirements

替换：

```
InMemoryKVStore
```

修改：

Command Engine

从：

```
Command

 |

InMemoryKVStore

```

变为：

```
Command

 |

StorageEngine

 |

MemTable
```

要求：

Phase1所有Command测试继续通过。

---

# 14. Benchmark Requirements

建立：

```
benchmarks/storage/
```

测试：

## GET

数据量：

```
10K

100K

1M
```

指标：

```
Throughput

P50

P95

P99
```

## Concurrent Write

测试：

```
10 threads

50 threads

100 threads
```

输出：

```
docs/benchmark/memory-engine-report.md
```

目标：

GET:

P99 < 0.5ms

100线程读写稳定。

---

# 15. Documentation Update

必须更新：

## README.md

增加：

Memory Engine Architecture

---

## ROADMAP.md

更新：

```
Phase 2 Completed
```

---

## AGENT_CONTEXT.md

更新：

```
Current Phase:

Phase 2 Completed


Storage:

SkipList MemTable


Next:

Eviction Policy
```

---

## CHANGELOG.md

增加：

```
feat(storage):

implement memory tier engine
```

---

# 16. Git Workflow

开始前：

创建：

```
git tag checkpoint-before-phase2-memory
```

完成后提交：

至少：

```
feat(storage):

implement MemTable engine


feat(storage):

add TTL manager


feat(memory):

add striped lock concurrency


test(storage):

add memory engine tests


docs:

add memory ADRs
```

---

# 17. Completion Criteria

Phase 2完成必须满足：

[ ] StorageEngine接口完成

[ ] MemTable完成

[ ] SkipList实现

[ ] PUT/GET/DELETE迁移完成

[ ] TTL支持完成

[ ] MemoryManager完成

[ ] 并发测试通过

[ ] Phase1测试全部通过

[ ] Benchmark完成

[ ] ADR-0007完成

[ ] ADR-0008完成

[ ] ADR-0009完成

[ ] Git Commit完成

---

# 18. Final Report

完成后输出：

```
# Phase 2 Completion Report


Architecture:

...


Implemented:

...


ADR:

...


Tests:

...


Benchmark:

...


Performance:

...


Git Commit:

...


Known Limitations:

...


Next Phase:

Phase 3 Cache Eviction (LFU / ARC)

```

---

# Start Phase 2

执行顺序：

1.

需求分析

2.

设计MemTable架构

3.

生成ADR

4.

创建测试

5.

实现代码

6.

Benchmark

7.

Git提交

不要直接开始编码。
