# Phase 4 Task: WAL Persistence & Recovery Layer

状态：✅ 已完成（2026-08-10）

## Task Identity

Project:

Tiering-KV

Phase:

Phase 4

Module:

Write Ahead Logging (WAL) & Recovery Engine

Goal:

实现高可靠 WAL 持久化层，为 Memory Tier 提供崩溃恢复能力。

建立：

Write → WAL → MemTable

的数据一致性模型。

---

# 1. Execution Rules

开始任务前必须读取：

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

- 直接修改 MemTable 绕过 WAL
- 未定义 WAL 格式直接写文件
- 使用 Object Serialization 作为日志格式
- 无 checksum
- 无 crash recovery 测试
- 删除失败测试

---

# 2. Phase Objective

当前 Phase 3 架构：

```

Command

|

StorageEngine

|

MemTable

|

Eviction Layer

```

升级为：

```

Command

|

StorageEngine

|

Write Path

|

WAL Manager

|

MemTable

|

Recovery Engine

```

实现：

Durability Layer。

---

# 3. Consistency Model

必须明确写入顺序。

推荐：

```

Client Request

```

|

|

```

Append WAL

```

|

|

```

fsync / group commit

```

|

|

```

Apply MemTable

```

|

|

```

Return Success

```

要求：

WAL 持久化成功后才能返回成功。

---

# 4. WAL Architecture

新增模块：

```

src/main/storage/wal/

├── WALManager

├── WALWriter

├── WALReader

├── WALEntry

├── WALRecord

├── LogSegment

├── SegmentManager

├── ChecksumValidator

├── RecoveryManager

└── CheckpointManager

```

---

# 5. WAL Record Design

必须设计稳定日志格式。

禁止：

Java Object Serialization。

推荐：

```

+----------------+

Magic

Version

Type

Timestamp

KeyLength

ValueLength

Payload

Checksum

+----------------+

```

支持：

## PUT

记录：

```

key

value

ttl

version

```

---

## DELETE

记录：

```

key

tombstone

version

```

---

# 6. WAL Entry

定义：

```

WALEntry

```

至少包含：

```

operationType

timestamp

key

value

ttl

version

checksum

```

---

# 7. Log Segment Design

WAL 不允许无限增长。

实现：

```

wal/

000001.log

000002.log

000003.log

```

要求：

支持：

- segment rolling
- max size配置
- segment recovery

---

# 8. Write Strategy ADR

必须生成 ADR。

## ADR-0013

文件：

```

docs/adr/ADR-0013-wal-write-strategy.md

```

标题：

```

WAL Write Strategy Selection

```

比较：

## Sync WAL

```

write

fsync

ack

```

优点：

安全

缺点：

性能低

---

## Async WAL

```

buffer

background flush

```

优点：

性能高

缺点：

可能丢失数据

---

## Group Commit

比较：

- batch size
- flush interval
- durability

最终选择。

---

# 9. WAL Format ADR

## ADR-0014

文件：

```

docs/adr/ADR-0014-wal-record-format.md

```

记录：

比较：

Binary Format

Text Format

Protobuf

最终方案。

必须包含：

- version兼容
- checksum
- forward compatibility

---

# 10. Recovery Design ADR

## ADR-0015

文件：

```

docs/adr/ADR-0015-crash-recovery-strategy.md

```

记录：

启动恢复流程：

```

Open WAL

↓

Validate checksum

↓

Replay records

↓

Rebuild MemTable

↓

Open Service

```

---

# 11. WAL Manager Design

职责：

```

append(entry)

flush()

rotate()

recover()

checkpoint()

```

---

# 12. MemTable Integration

修改写路径。

当前：

```

SET

|

MemTable.put()

```

改为：

```

SET

|

WAL.append()

|

MemTable.put()

```

要求：

Command层无感。

---

# 13. Recovery Engine

启动流程：

```

Application Start

```

    |

```

RecoveryManager

```

    |

```

Scan WAL

```

    |

```

Replay

```

    |

```

Restore MemTable

```

    |

```

Accept Requests

```

必须支持：

## 正常恢复

最后状态一致。

---

## 部分写入恢复

模拟：

```

WAL write incomplete

```

要求：

丢弃损坏尾部。

---

## checksum失败

要求：

检测并停止错误segment。

---

# 14. Checkpoint Design

实现：

```

CheckpointManager

```

作用：

减少恢复时间。

保存：

```

MemTable snapshot point

*

WAL offset

```

恢复：

```

Load checkpoint

*

Replay remaining WAL

```

---

# 15. Testing Requirements

必须先写测试。

目录：

```

tests/unit/wal/

```

必须包含：

```

WALEntryTest

WALWriterTest

WALReaderTest

SegmentRotationTest

ChecksumTest

RecoveryManagerTest

CrashRecoveryTest

CheckpointTest

```

---

# 16. Crash Simulation

必须模拟：

## Case 1

写入成功：

```

WAL

*

MemTable

```

恢复一致。

---

## Case 2

WAL存在：

MemTable不存在。

恢复：

Replay。

---

## Case 3

损坏最后record。

恢复：

忽略尾部。

---

# 17. Benchmark Requirements

新增：

```

benchmarks/wal/

```

测试：

## WAL Append

数据：

```

100K records

1M records

```

指标：

```

append latency

P50

P95

P99

throughput

```

---

## Recovery Benchmark

测试：

```

100K WAL entries

1M WAL entries

```

指标：

```

recovery time

```

目标：

```

append P99 < 1ms

1M records recovery < seconds

```

---

# 18. Documentation Update

更新：

## README

增加：

```

Durability Layer

WAL Architecture

```

---

## ROADMAP

更新：

```

Phase 4 Completed

```

---

## AGENT_CONTEXT

更新：

```

Current Phase:

Phase 4 Completed

Persistence:

WAL enabled

Next:

LSM / Bitcask Storage Engine

```

---

## CHANGELOG

增加：

```

feat(storage):

implement WAL persistence and recovery

```

---

# 19. Git Workflow

开始：

创建：

```

git tag checkpoint-before-phase4-wal

```

提交：

```

feat(wal):

implement WAL manager

feat(wal):

add log segment

feat(storage):

integrate WAL with MemTable

feat(recovery):

implement crash recovery

test(wal):

add WAL tests

docs:

add WAL ADRs

```

---

# 20. Completion Criteria

Phase 4完成：

[x] WAL格式设计完成

[x] WAL append完成

[x] Segment管理完成

[x] checksum完成

[x] Recovery完成

[x] Checkpoint完成

[x] MemTable写路径接入WAL

[x] Crash测试通过

[x] Phase1/2/3全部回归通过

[x] Benchmark完成

[x] ADR-0013完成

[x] ADR-0014完成

[x] ADR-0015完成

[x] Git Commit完成

---

# 21. Final Report

完成后输出：

```

# Phase 4 Completion Report

Architecture:

...

WAL Format:

...

Write Strategy:

...

Recovery:

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

Phase 5 LSM / Bitcask Storage Engine

```

---

# Start Phase 4

执行顺序：

1.  读取项目上下文

2.  分析WAL需求

3.  生成ADR

4.  设计日志格式

5.  编写测试

6.  实现WAL

7.  Crash Recovery验证

8.  Benchmark

9.  Git提交

不要直接开始编码。

## 验收结果

- `mvn test`：全量用例全绿（Phase 1–3 全部回归通过）。
- ADR 编号调整：0013 已被 TierMigration 占用，本阶段实际产出为
  ADR-0014（写策略）、ADR-0015（记录格式）、ADR-0016（崩溃恢复）。
- 基准：WAL append P99 < 1ms；1M 记录恢复 < 秒级（详见
  docs/benchmark/wal-report.md）。
- 实测：append P99=0.0068ms（100K）/ 0.0015ms（1M）；
  恢复 92ms（100K）/ 0.57s（1M）。

```

```
