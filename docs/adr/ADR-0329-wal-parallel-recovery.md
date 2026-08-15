# ADR-0329: WAL Parallel Recovery

## Status

Accepted

## Context

TD-007：恢复为单线程逐段扫描（1M 记录 ≈ 1s），解码/CRC 校验为主要
成本且段间可并行；应用须保持段内顺序（MemTable 语义）。

## Decision

- 新增 `ParallelRecoveryManager`：按段并行解析（每段独立 WALReader
  解码 + CRC 校验），按段序号串行应用；
- 段解析经固定线程池（默认 CPU 核数），主线程按序消费 Future
  （前面段解析完成后立即应用，后面段并行解析——流水线）；
- 中段损坏：该段截断尾部，停止后续段重放（与现有语义一致）；
- TTL/删除语义与 RecoveryManager.apply 完全一致；
- 现有 RecoveryManager 保留（兼容），ParallelRecoveryManager 作为
  默认演进候选（WALManager 可切换）。

## Alternatives

1. 全量并行应用：MemTable 写入顺序竞争，语义破坏；
2. 保持单线程：1M 恢复 1s 可接受，但段数增长后线性退化。

## Consequences

优点：解码/IO 并行，恢复延迟随段数扩展。

缺点：内存峰值 = 单段 entry 列表（远小于全量日志）。

风险：段内顺序必须保持（Future 按序消费保证）。

## Implementation

`storage/wal/ParallelRecoveryManager.java` + 测试。
