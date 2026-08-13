# ADR-0267: JMH Benchmark Engineering

## Status

Accepted

## Context

全部 benchmark 为手写循环 + System.nanoTime，缺少预热/迭代/统计，
结果可复现性差，无法支撑性能回归。

## Decision

采用 JMH 基准工程化：

- 引入 jmh-core / jmh-generator-annprocess / jmh-maven-plugin；
- 核心路径迁移：MemTable GET/SET、WAL append、SSTable 随机读
  （mmap + block cache）至少 3 条；
- 固定 fork / warmup / iterations / seed（脚本可配置）；
- `scripts/benchmark-jmh.sh` 一键运行；
- 基准报告（docs/benchmark/jmh-core-report.md）+ 口径注明。

## Alternatives

1. 继续手写循环：无预热无统计，结果不可信；
2. 引入完整基准框架（如 Renaissance）：过重；
3. JMH 只做演示不落地核心路径：没有工程价值。

## Consequences

优点：性能可复现、可回归、可比较。

缺点：JMH 运行时间长，需要独立于测试套件执行。

风险：环境差异（CPU/GC/磁盘）仍影响绝对值，需记录环境指纹。

## Implementation

`pom.xml`、`benchmarks/jmh/` 骨架、
`src/test/java/io/tieringkv/benchmark/jmh/` 基准类、
`scripts/benchmark-jmh.sh`、`docs/benchmark/jmh-core-report.md`。
