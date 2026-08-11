# ADR-0097: Backup Restore Strategy

## Status

Accepted

## Context

生产环境需要可恢复的备份：元数据决策、MVCC 索引与存储快照必须一致。

## Decision

- `BackupManager`：原子生成备份目录
  （元数据 lifecycle+entries、MVCC 索引文件、存储快照）；
- `RestoreManager`：从备份恢复元数据与索引，事务可读；
- 备份文件沿用既有格式（TxnMetaCodec / PersistentMvccIndex），
  带校验（CRC）。

## Alternatives

1. 仅 Raft 快照：元数据与业务索引不一致。
2. 全量磁盘拷贝：依赖外部工具。

## Consequences

优点：备份/恢复闭环可验证。风险：低。

## Implementation

- `backup/`：BackupManager、RestoreManager；
- 测试：BackupRestoreTest。
