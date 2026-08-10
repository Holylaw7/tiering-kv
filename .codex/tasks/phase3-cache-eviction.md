# Phase 3 Task: Hot Data Management Layer (LFU / ARC Eviction)

## Task Identity

Project:

Tiering-KV

Phase:

Phase 3

Module:

Hot Data Management Layer

Goal:

在 Memory Tier 基础上实现数据热度分析、淘汰策略和冷热迁移接口。

本阶段不实现磁盘存储。

重点：

完成 Memory Tier → Cold Tier 的决策层。

---

# 1. Execution Rules

开始前必须读取：

```

.codex/MASTER_PROMPT.md

.codex/DEVELOPMENT_RULES.md

.codex/AGENT_CONTEXT.md

ROADMAP.md

docs/adr/

最近 Git Log

```

严格执行：

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

Git Commit

```

禁止：

- 直接修改 MemTable 核心结构绕过接口
- 将淘汰逻辑写入 Command Layer
- 使用简单 LRU 替代要求的 LFU/ARC
- 删除已有测试
- 未生成 ADR 修改策略

---

# 2. Phase Objective

当前架构：

```

Command

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

MemoryManager

```

升级为：

```

Command

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

Hotness Tracker

```

|

```

Eviction Manager

```

|

```

Migration Interface

```

实现：

Memory Tier 管理能力。

---

# 3. Functional Requirements

## 3.1 Hotness Tracking

必须实现访问热度统计。

每次：

GET

SET

UPDATE

DELETE

需要产生访问事件。

例如：

```

AccessEvent

{

key,

operation,

timestamp

}

```

---

# 4. Hotness Algorithm

必须实现：

## LFU

要求：

支持：

- frequency counter
- access timestamp
- decay

数据模型：

```

HotnessEntry

key

frequency

lastAccessTime

createTime

```

---

## Frequency Decay

必须解决：

热点永久占用问题。

实现：

时间衰减。

例如：

```

frequency = frequency / 2

every decay interval

```

要求：

Decay 周期可配置。

---

# 5. ARC Prototype

实现 ARC 算法实验版本。

目录：

```

storage/cache/arc

```

包含：

```

ARCPolicy

T1

T2

B1

B2

```

要求：

记录：

- 最近访问
- 高频访问
- ghost entry

目标：

比较 LFU 与 ARC 效果。

---

# 6. Architecture Design

新增模块：

```

src/main/storage/cache/

├── HotnessTracker

├── FrequencyCounter

├── LFUPolicy

├── ARCPolicy

├── EvictionManager

├── EvictionCandidate

├── MigrationCallback

└── AccessEvent

```

---

# 7. Eviction Manager

职责：

监控：

```

MemoryManager.usedMemory

```

当：

```

usedMemory > maxMemory

```

触发：

```

EvictionManager

```

|

```

选择candidate

```

|

```

执行migration callback

```

---

# 8. Migration Interface

本阶段不实现磁盘。

必须设计接口：

```

ColdStorageMigration

```

例如：

```java

interface MigrationCallback {


 void migrate(KeyValueEntry entry);


}

```

未来连接：

Phase 5:

```
Bitcask

LSM

SSTable

```

---

# 9. Eviction Candidate Selection

必须支持：

输入：

```
Memory Snapshot

```

输出：

```
EvictionCandidate

```

包含：

```
key

frequency

lastAccess

size

score

```

---

# 10. Eviction Score Design

必须生成 ADR。

需要定义：

例如：

LFU:

```
score = frequency
```

或者：

综合：

```
score =
frequency_weight

+

age_weight

+

size_weight

```

---

# 11. ADR Requirements

必须生成：

## ADR-0010

文件：

```
docs/adr/ADR-0010-hotness-tracking-strategy.md
```

标题：

```
Hotness Tracking Strategy
```

比较：

- Counter
- LFU
- Sliding Window
- TinyLFU

说明最终选择。

---

## ADR-0011

文件：

```
docs/adr/ADR-0011-lfu-decay-algorithm.md
```

标题：

```
LFU Frequency Decay Algorithm
```

记录：

- decay周期
- 衰减方式
- 精度影响

---

## ADR-0012

文件：

```
docs/adr/ADR-0012-arc-policy-evaluation.md
```

标题：

```
ARC Policy Evaluation
```

比较：

- LFU
- ARC
- TinyLFU

---

# 12. TDD Requirements

必须先写测试。

目录：

```
tests/unit/cache/
```

必须包含：

```
HotnessTrackerTest


LFUPolicyTest


LFUDecayTest


ARCPolicyTest


EvictionManagerTest


MigrationCallbackTest

```

覆盖：

## LFU

- frequency increase
- ranking
- decay

## ARC

- T1 movement
- T2 movement
- ghost list

## Eviction

- memory limit trigger
- candidate selection

---

# 13. Integration Requirements

接入：

```
MemoryManager
```

流程：

```
PUT


 |

MemTable


 |

MemoryManager


 |

capacity check


 |

EvictionManager


 |

MigrationCallback

```

要求：

已有 Phase 2 测试全部通过。

---

# 14. Benchmark Requirements

建立：

```
benchmarks/cache/
```

测试：

## LFU Performance

数据：

```
100K keys

1M accesses
```

指标：

```
lookup latency

update latency

memory overhead

```

---

## Eviction Performance

测试：

```
100K entries

1M entries
```

指标：

```
candidate selection latency

```

目标：

```
Eviction decision < 1ms

```

---

# 15. Documentation Update

更新：

## README

增加：

Hot Data Management Architecture

---

## ROADMAP

更新：

```
Phase 3 Completed
```

---

## AGENT_CONTEXT

更新：

```
Current Phase:

Phase 3 Completed


Hotness:

LFU + ARC


Next:

WAL Persistence
```

---

## CHANGELOG

增加：

```
feat(cache):

implement hot data management layer
```

---

# 16. Git Workflow

开始：

创建 checkpoint:

```
git tag checkpoint-before-phase3-cache
```

提交：

```
feat(cache):

implement hotness tracker


feat(cache):

implement LFU eviction


feat(cache):

add ARC prototype


feat(storage):

add eviction manager


test(cache):

add eviction tests


docs:

add cache ADRs

```

---

# 17. Completion Criteria

Phase 3 完成：

[ ] HotnessTracker完成

[ ] LFU完成

[ ] LFU decay完成

[ ] ARC prototype完成

[ ] EvictionManager完成

[ ] Migration接口完成

[ ] MemoryManager集成完成

[ ] 单元测试完成

[ ] Benchmark完成

[ ] ADR-0010完成

[ ] ADR-0011完成

[ ] ADR-0012完成

[ ] Git commit完成

---

# 18. Final Report

完成后输出：

```
# Phase 3 Completion Report


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


Git:


...


Known Limitations:


...


Next Phase:

Phase 4 WAL Persistence

```

---

# Start Phase 3

执行顺序：

1.

读取项目上下文

2.

分析需求

3.

生成ADR

4.

设计接口

5.

编写测试

6.

实现LFU/ARC

7.

Benchmark

8.

提交Git

不要直接编码。

```

```
