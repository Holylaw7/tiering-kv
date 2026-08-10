# Phase 8 Task: IO Optimization Layer

状态：✅ 已完成（2026-08-10）

# 1. Task Identity

Project:

Tiering-KV

Phase:

Phase 8

Module:

IO Optimization

Goal:

优化磁盘访问路径，引入 mmap、零拷贝、Off-Heap Buffer、
Block Cache，提高冷数据读取性能并降低 JVM 内存压力。

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

Phase 1-7 Completion Reports

git log

```

必须遵循：

```

需求分析

↓

IO瓶颈分析

↓

ADR设计

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

- 修改 WAL 语义
- 修改 SSTable 数据格式（除非新增兼容版本）
- 破坏已有 Recovery
- 绕过 Storage SPI
- 使用不稳定 Unsafe 黑科技
- 直接替换成熟组件而不评估

---

# 3. Current Architecture

当前：

```

GET

|

StorageEngine

|

SSTableReader

|

FileChannel.read()

|

Heap byte[]

|

Decode

```

问题：

```

Disk IO

*

Heap Allocation

*

GC Pressure

*

Copy Cost

```

---

# 4. Target Architecture

升级：

```

GET

|

StorageEngine

|

BlockCache

|

SSTableReader

|

+----------------+

|                |

mmap            FileChannel

|                |

MappedByteBuffer ByteBuffer

```

    |

```

Zero Copy Decode

```

    |

```

Response

```

---

# 5. Required ADR

## ADR-0026

文件：

```

docs/adr/ADR-0026-sstable-io-strategy.md

```

标题：

```

SSTable IO Strategy Selection

```

比较：

- FileChannel read
- mmap
- async IO
- io_uring（调研）

确定：

生产读取方案。

必须分析：

- latency
- throughput
- memory usage
- OS page cache

---

## ADR-0027

文件：

```

docs/adr/ADR-0027-offheap-memory-strategy.md

```

标题：

```

Off-Heap Memory Strategy

```

比较：

- Heap byte[]
- DirectByteBuffer
- MemorySegment
- Arena allocation

确定：

Buffer 生命周期管理。

---

## ADR-0028

文件：

```

docs/adr/ADR-0028-block-cache-strategy.md

```

标题：

```

SSTable Block Cache Strategy

```

比较：

- LRU
- LFU
- TinyLFU
- ARC

确定：

Block Cache 淘汰策略。

---

# 6. mmap SSTable Reader

新增模块：

```

storage/io/

├── MmapSSTableReader

├── MappedFile

├── FileRegion

├── BlockDecoder

└── IOStatistics

```

要求：

支持：

```

open()

map()

readBlock()

close()

```

---

# 7. mmap Design Requirements

实现：

```

SSTable File

|

MappedByteBuffer

|

offset lookup

|

block decode

```

必须支持：

- block offset 定位
- CRC 校验
- Footer 校验
- 文件关闭释放

---

# 8. FileChannel vs mmap

必须保留：

```

FileChannelSSTableReader

```

作为 baseline。

Benchmark比较：

```

FileChannel

vs

mmap

```

指标：

```

latency

throughput

allocation

GC

```

---

# 9. Zero Copy Optimization

目标：

减少：

```

Disk

|

Kernel Buffer

|

Heap byte[]

|

Network Buffer

```

优化：

```

MappedByteBuffer

```

    |

```

Direct Buffer

```

    |

```

Encoder

```

要求：

记录：

- copy次数
- buffer生命周期
- 内存占用

---

# 10. Off-Heap Memory Pool

新增：

```

memory/

├── MemoryPool

├── BufferArena

├── DirectBufferPool

├── BufferRecycler

└── AllocationTracker

```

功能：

支持：

```

allocate(size)

release(buffer)

reuse()

```

---

# 11. Memory Pool Requirements

必须统计：

```

allocated bytes

released bytes

reuse count

peak memory

```

目标：

减少：

```

byte[]

对象创建

GC次数

```

---

# 12. Block Cache

新增：

```

cache/block/

├── BlockCache

├── CacheEntry

├── CacheKey

├── CachePolicy

└── CacheStatistics

```

缓存对象：

```

SSTable Block

```

---

# 13. Block Cache Strategy

默认：

根据 ADR选择。

要求：

支持：

```

get(blockKey)

put(blockKey,data)

invalidate()

clear()

```

---

# 14. Cache Integration

读取流程：

优化前：

```

GET

|

SSTable

|

Disk

```

优化后：

```

GET

|

BlockCache

|

hit

|

return

miss

|

mmap read

|

cache insert

```

---

# 15. IO Statistics

新增：

```

IOStatistics

```

指标：

```

read count

cache hit

cache miss

mapped bytes

page fault

read latency

```

---

# 16. Benchmark Requirements

新增：

```

benchmarks/io/

```

---

# 17. mmap Benchmark

数据规模：

```

100K keys

1M keys

10M keys

```

测试：

```

random GET

sequential GET

```

指标：

```

P50

P95

P99

throughput

```

---

# 18. Block Cache Benchmark

测试：

```

cold read

warm read

mixed workload

```

比较：

```

cache disabled

cache enabled

```

---

# 19. Memory Benchmark

统计：

```

heap usage

offheap usage

GC count

allocation rate

```

工具：

允许：

```

JFR

jcmd

VisualVM

```

---

# 20. Performance Target

目标：

## Cold Read

随机读取：

```

P99 < 5ms

```

---

## Warm Read

Block Cache命中：

```

P99 < 1ms

```

---

## Memory

减少：

```

heap allocation >50%

```

---

## Throughput

随机读取：

```

> 500K ops/s

```

---

# 21. Testing Requirements

新增：

```

tests/io/

```

必须包含：

```

MmapReaderTest

FileChannelReaderTest

BlockCacheTest

MemoryPoolTest

IORecoveryTest

```

覆盖：

- mmap正确读取
- 文件损坏检测
- CRC错误
- cache一致性
- buffer释放
- 多线程读取
- 重启恢复

---

# 22. Compatibility Requirements

必须保证：

Phase1-7:

```

RESP

Command

WAL

Recovery

SSTable

Migration

Concurrency

```

全部回归通过。

---

# 23. Documentation Update

更新：

README:

增加：

```

IO Architecture

```

ROADMAP:

```

Phase8 Completed

```

AGENT_CONTEXT:

增加：

```

IO Optimization:

mmap enabled

block cache enabled

offheap pool enabled

```

CHANGELOG:

```

feat(io):

add mmap and zero-copy optimization

```

---

# 24. Git Workflow

开始：

```

git tag checkpoint-before-phase8-io

```

提交规范：

```

docs:
add IO ADR

feat(io):
implement mmap reader

feat(memory):
add offheap buffer pool

feat(cache):
implement block cache

test:
add IO tests

perf:
add IO benchmark report

```

---

# 25. Completion Criteria

Phase8完成：

[x] ADR-0026完成

[x] ADR-0027完成

[x] ADR-0028完成

[x] mmap reader完成

[x] FileChannel baseline保留

[x] Block Cache完成

[x] OffHeap Pool完成

[x] Zero-copy路径完成

[x] IO Statistics完成

[x] Benchmark完成

[x] GC分析完成

[x] Phase1-7回归通过

[x] Git merge完成

## 验收结果

- `mvn test`：全量用例全绿（Phase 1–7 全部回归通过）。
- mmap 读取器 + FileChannel baseline；Block Cache（LRU + 池化缓冲）；
  Off-Heap MemoryPool（复用统计）；IOStatistics。
- 基准（docs/benchmark/io-report.md）：随机读 P99 0.012–0.040ms；
  缓存命中率 94.8%；mmap 较 FileChannel 提速 ~2×；GC 计数样本 +3。

---

# 26. Final Report Format

输出：

```

# Phase 8 Completion Report

Architecture:

...

IO Strategy:

...

mmap Design:

...

Block Cache:

...

OffHeap Memory:

...

ADR:

...

Tests:

...

Benchmark:

...

Performance:

...

Memory Profile:

...

Git:

...

Known Limitations:

...

Next Phase:

Phase 9 Production Benchmark

```

---

# Start Phase 8

执行顺序：

1.  读取 Phase1-7 架构

2.  分析 IO 瓶颈

3.  生成 ADR

4.  设计 mmap / cache / memory pool

5.  实现 baseline

6.  实现优化路径

7.  Benchmark 对比

8.  GC / Memory Profile

9.  Code Review

10. Git Commit

禁止跳过设计阶段直接编码。

```

```
