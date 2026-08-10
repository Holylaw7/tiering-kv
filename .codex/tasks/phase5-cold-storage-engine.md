# Phase 5 Task: Cold Storage Engine Layer

状态：✅ 已完成（2026-08-10）

# 1. Task Identity

Project:

Tiering-KV

Phase:

Phase 5

Module:

Cold Storage Engine

Goal:

在 WAL 基础上实现磁盘冷存储层，实现完整冷热分层架构。

---

# 2. Execution Rules

开始前必须读取：

.codex:

```

MASTER_PROMPT.md

DEVELOPMENT_RULES.md

AGENT_CONTEXT.md

tasks history

```

项目文档：

```

docs/architecture/

docs/adr/

docs/design/

ROADMAP.md

```

Git:

```

git log

git branch

git status

```

---

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

---

禁止：

- 直接修改 MemTable 绕过 Storage SPI
- 删除 WAL
- 使用随机 IO 模拟存储
- 不经过 ADR 修改存储策略
- 先写代码后设计
- 删除已有测试

---

# 3. Phase Objective

当前架构：

```

Command

|

WALStorageEngine

|

MemTable

```

升级：

```

Command

|

TieredStorageEngine

```

    |

    |

```

Memory Tier

(MemTable)

```

    |

```

Cold Tier

(SSTable / Bitcask)

```

    |

```

Disk

```

---

# 4. Storage Strategy Decision

本阶段必须先生成 ADR。

需要比较：

## Bitcask

特点：

- append only
- hash index
- 高写性能

缺点：

- 空间放大
- merge复杂

---

## LSM Tree

特点：

- 顺序写
- SSTable
- compaction

缺点：

- 实现复杂

---

## Final Decision

推荐：

LSM + Bitcask Log

架构：

```

WAL

|

MemTable

|

Flush

|

SSTable

*

Append Log

```

---

# 5. Required ADR

## ADR-0017

文件：

```

docs/adr/ADR-0017-cold-storage-strategy.md

```

标题：

```

Cold Storage Strategy Selection

```

内容：

比较：

- Bitcask
- LSM
- B+Tree

最终选择。

---

## ADR-0018

文件：

```

docs/adr/ADR-0018-sstable-format.md

```

标题：

```

SSTable File Format Design

```

必须说明：

- block结构
- index
- footer
- checksum
- version

---

## ADR-0019

文件：

```

docs/adr/ADR-0019-compaction-strategy.md

```

标题：

```

Compaction Strategy

```

比较：

- size tiered
- leveled compaction

---

# 6. Module Design

新增：

```

storage/cold/

├── ColdStorageEngine

├── SSTable

├── SSTableWriter

├── SSTableReader

├── Block

├── BlockIndex

├── BloomFilter

├── Manifest

├── CompactionManager

├── CompactionTask

└── DiskIterator

```

---

# 7. Tiered Storage Interface

设计：

```java

interface TierStorage {


    Optional<Value> get(Key key);


    void put(KeyValueEntry entry);


    void delete(Key key);


}

```

要求：

Memory Tier:

```
MemTable
```

Cold Tier:

```
SSTable
```

实现统一。

---

# 8. MemTable Flush

实现：

```
MemTable

     |

immutable snapshot

     |

SSTableWriter

     |

disk file

```

要求：

Flush过程中：

- 不阻塞读
- 保证一致性

---

# 9. SSTable Design

文件结构：

```
+----------------+

Data Blocks


+----------------+

Index Block


+----------------+

Bloom Filter


+----------------+

Footer


+----------------+

```

---

## Data Block

包含：

```
key

value

timestamp

version

tombstone

ttl
```

---

## Index Block

保存：

```
firstKey

offset

size
```

---

## Footer

包含：

```
magic

version

checksum

offset
```

---

# 10. Bloom Filter

实现：

```
storage/cold/filter/
```

要求：

支持：

```
mightContain(key)
```

参数：

可配置：

- bits per key
- hash count

---

测试：

false positive rate。

目标：

<1%

---

# 11. SSTable Reader

支持：

```
GET


SCAN


ITERATOR
```

流程：

```
Query key


 |

Bloom Filter


 |

Index


 |

Block


 |

Value

```

---

# 12. Compaction

实现：

```
CompactionManager
```

支持：

Level0:

```
multiple SSTables
```

merge:

```
SSTable A

+

SSTable B


↓

SSTable C

```

必须处理：

- duplicate key
- tombstone
- expired TTL

---

# 13. Migration Integration

连接 Phase3。

升级：

```
EvictionManager


        |

MigrationCallback


        |

ColdStorageEngine

```

流程：

```
Memory Full


↓

Select Candidate


↓

Write Cold Storage


↓

Verify


↓

Remove Memory Entry

```

---

# 14. WAL Integration

写流程：

```
SET


 |

WAL


 |

MemTable


 |

Flush


 |

SSTable

```

要求：

SSTable flush后：

WAL checkpoint。

---

# 15. Testing Requirements

新增：

```
tests/unit/cold/
```

必须包含：

```
SSTableWriterTest

SSTableReaderTest

BloomFilterTest

FlushTest

CompactionTest

ColdStorageEngineTest

MigrationIntegrationTest

```

覆盖：

- SSTable读写
- checksum
- Bloom false positive
- flush一致性
- compaction
- tombstone删除
- TTL过期
- 重启恢复

---

# 16. Benchmark Requirements

新增：

```
benchmarks/cold/
```

测试：

## SSTable Write

数据：

```
100K

1M keys
```

指标：

```
write throughput

file size
```

---

## Disk Read

测试：

```
random GET
```

指标：

```
P50

P95

P99
```

---

## Bloom Filter

指标：

```
false positive rate
```

---

## Compaction

指标：

```
merge throughput

temporary disk usage
```

---

# 17. Performance Target

目标：

## SSTable

```
write > 100MB/s
```

## Read

随机GET:

```
P99 < 5ms
```

## Bloom

```
false positive <1%
```

---

# 18. Documentation Update

更新：

README:

增加：

```
Cold Storage Architecture
```

ROADMAP:

```
Phase 5 Completed
```

AGENT_CONTEXT:

```
Current Phase:

Phase5 completed


Storage:

LSM/SSTable enabled
```

CHANGELOG:

```
feat(storage):

implement cold storage engine
```

---

# 19. Git Workflow

开始：

```
git tag checkpoint-before-phase5-cold-storage
```

提交：

```
docs:

add cold storage ADR


feat(storage):

implement SSTable


feat(storage):

add bloom filter


feat(storage):

implement compaction


feat(storage):

integrate memory migration


test(storage):

add cold storage tests


perf:

add cold benchmark

```

---

# 20. Completion Criteria

Phase5完成：

[x] Cold Storage Strategy ADR完成

[x] SSTable完成

[x] Bloom Filter完成

[x] Reader/Writer完成

[x] MemTable Flush完成

[x] Compaction完成

[x] Migration完成

[x] WAL checkpoint接入

[x] Crash recovery验证

[x] Benchmark完成

[x] Phase1-4回归通过

[x] Git merge完成

## 验收结果

- `mvn test`：167 用例全绿（Phase 1–4 全部回归通过；新增 22）。
- 基准：1M 写 104MB/s、随机 GET P99=0.021ms、Bloom FPR=0.82%、
  合并 46.6MB/s（详见 docs/benchmark/cold-report.md）。
- ADR-0017 / 0018 / 0019 已生成；写路径 = WAL → MemTable → Flush → SSTable；
  淘汰迁移 = 写冷层 → 验证 → 删内存（WAL DELETE 防复活）。

---

# 21. Final Report Format

输出：

```
# Phase 5 Completion Report


Architecture:

...


Storage Strategy:

...


SSTable Format:

...


Bloom Filter:

...


Compaction:

...


Migration:

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

Phase 6 Tiering Optimization
```

---

# Start Phase 5

执行顺序：

1. 阅读历史ADR

2. 分析LSM/Bitcask选择

3. 创建ADR

4. 设计SSTable格式

5. 编写测试

6. 实现Cold Storage

7. 接入Migration

8. Benchmark

9. Code Review

10. Git提交

不要直接编码。

```

```
