# ADR-0271: Multi-Key Batch Semantics

## Status

Accepted

## Context

缺少 MGET/MSET/MSETNX 与 DEL/EXISTS 多键语义；批量写入需要
单命令原子性与统一返回值。

## Decision

采用 StorageEngine.applyBatch 批量语义：

- MGET：逐键 get，缺失返回 nil；
- MSET：键值成对校验，经 applyBatch 应用，单命令内同段原子
  （跨段由网关 CROSSSLOT 限制同槽，见 ADR-0274）；
- MSETNX：任一键存在返回 0，全部不存在才写入返回 1；
- DEL/EXISTS：多键返回受影响/存在数量；
- WAL 以逐 PUT 记录重放（崩溃中点重放幂等）。

## Alternatives

1. 逐键 get+put：MSET 半程可见；
2. 跨段全局锁：破坏并发；
3. 新增批量 WAL 记录：冻结格式变更。

## Consequences

优点：命令面完整、批量为同段原子、网关同槽保证集群语义。

缺点：跨段 MSET 非整体原子（文档明确）。

风险：需要网关 CROSSSLOT 约束才能承诺集群级语义。

## Implementation

`command/` 多键命令族 + `src/test/java/io/tieringkv/command/MultiKeyCommandTest.java`。
