# ADR-0179: Remote Materialization State Persistence

## Status

Accepted

## Context

Phase 37 的远端物化增量状态仅在内存，重启丢失需全量回退（TD-064）；
需要落盘恢复以保持增量语义。

## Decision

1. `datamesh/RemoteStateStore`：远端视图状态（key 值 + 快照 + stale）
   序列化落盘 + 恢复；
2. 恢复后增量语义不丢失；损坏/缺失回退全量刷新；
3. 与 RemoteMaterializationManager 联动；
4. 验收：落盘/恢复矩阵 + 损坏回退矩阵。

## Alternatives

1. 内存态：重启全量回退，恢复慢；
2. 数据库外部存储：依赖重。

## Consequences

优点：重启快速恢复，增量语义保留。

缺点：需要序列化格式维护。

风险：损坏文件由回退全量兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/remote-state-persistence.md`。
