# ADR-0029: Production Benchmark Methodology

## Status

Accepted

## Context

Phase 1–8 各模块基准口径不一（内存直连 / 网络 / 冷层），无法回答"单机最大
QPS、P99、瓶颈在哪"。需要统一方法与环境，建立可信生产基线。

## Decision

采用**三级基准 + 固定环境记录 + 多次采样**：

```text
Level A：内存引擎（ShardExecutor + MemTable + Cache，绕过网络/WAL/磁盘）
Level B：服务端（Client → RESP → Netty → CommandEngine → ShardExecutor →
         内存引擎）
Level C：生产全链路（+ WAL → MemTable → Flush → SSTable → BlockCache →
         迁移）
```

1. **环境冻结记录**：JVM（version/GC/heap/direct/JVM 参数）、硬件（CPU/内存/
   磁盘/OS/文件系统）、数据集（键数/值大小/冷热比例/访问分布）；
2. **指标统一**：Throughput、P50/P95/P99/P999、CPU、Memory、GC、IO；
3. **采样规则**：每场景 ≥3 轮，报告取中位数；异常数据不删除但标注归因；
   单次测试不作为结论；
4. **数据规模**：Small 100K / Medium 1M / Large 10M（Large 手动运行）；
5. **JVM 采集**：GC beans（次数/停顿）、AllocationTracker；JFR 由启动参数
   开启（`-XX:StartFlightRecording`），报告记录启用方式。

## Alternatives

1. 仅模块基准：口径不可比；
2. 仅端到端：无法定位瓶颈层；
3. 单次压测：不可信。

## Consequences

**优点：** 瓶颈可分层定位；环境可复现；容量模型有据可依。
**缺点：** 压测耗时；需冻结环境。
**风险：** 环境抖动 → 多轮中位数 + 记录负载。

## Implementation

- `benchmarks/production`：Level A/B/C 套件 + 管道 RESP 客户端；
- 报告：phase9-memory / server / production；容量模型（ADR-0030）。
