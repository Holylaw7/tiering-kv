# JMH Core Benchmark Guide

## 概述

JMH 基准（ADR-0267）覆盖三条核心路径：

| 基准 | 路径 | 参数 |
| --- | --- | --- |
| MemTableGetBenchmark | 内存 GET | size 10K / 100K |
| WalAppendBenchmark | WAL append（NO fsync 口径） | 固定 |
| SstableRandomReadBenchmark | mmap SSTable 随机读 | size 10K / 100K |

固定 fork=1、warmup=2×1s、measurement=3×1s，保证可复现。

## 运行

```bash
./scripts/benchmark-jmh.sh
./scripts/benchmark-jmh.sh MemTableGetBenchmark  # 单条路径
```

结果输出到 target/jmh-results/。

## 口径

- MemTable / WAL：本地进程内口径；
- SSTable：page cache 热口径（生产冷读另行登记）；
- 环境指纹（CPU/GC/磁盘）需随报告记录，禁止跨机直接比较。
