# Cross-Segment Transaction Persistence

## PersistentExecJournal

- 追加日志：MAGIC + txnId + commandCount + outcome + timestamp +
  CRC32C；
- 截断/损坏尾部在恢复时忽略；
- 重启后 txnId 继续递增；
- additive 文件，不修改 WAL/RPC 格式。

## 一致性

EXEC 结果落盘（审计可恢复）；严格跨命令原子事务仍走 MVCC 2PC
路径（Phase 19+）。
