# ADR-0301: Cross-Segment Transaction Persistence

## Status

Accepted

## Context

ExecJournal 为内存态，重启丢失审计与未完成事务信息。

## Decision

采用持久化 ExecJournal：

- 追加日志（记录长度 + CRC32C + outcome + 命令摘要）；
- EXEC 提交前落盘（崩溃一致性）；
- 恢复：已完成记录保留审计，截断尾部忽略；
- additive 文件（不修改 WAL/RPC 格式）。

## Alternatives

1. 复用 WAL：命令级事务与存储 WAL 语义不同；
2. 不落盘：审计丢失；
3. 全量快照：成本高。

## Consequences

优点：审计可恢复、崩溃一致。

缺点：额外写放大（事务低频可接受）。

风险：日志轮换需后续阶段。

## Implementation

`io.tieringkv.transaction.PersistentExecJournal` +
`src/test/java/io/tieringkv/transaction/PersistentExecJournalTest.java`、
`docs/transaction/cross-segment-txn-persistence.md`。
