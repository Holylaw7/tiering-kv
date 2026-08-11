# ADR-0104: Point In Time Recovery

## Status

Accepted

## Context

Phase 24 备份/恢复为快照口径（元数据快照 + MVCC 索引），只能恢复到
备份时刻。企业发布需要恢复到任意时间点（T1），即快照 + 后续写入日志
重放。

## Decision

新增 `backup/pitr/`：

1. `PitrWriteLog`：追加式变更日志（seq / commitTS / key / value /
   deleted / txnId），分段 + CRC32C，与 MVCC 写入路径联动；
2. `WALArchiveManager`：日志分段滚动、归档与按水位读取；
3. `CheckpointManager`：持久化检查点（快照字节 + 已归档水位 +
   时间戳）；
4. `RestoreTimeline`：恢复 = 加载快照 → 重放 commitTS <= 目标时间
   的归档日志。

验证闭环：write → snapshot → write more → crash → restore T1 →
verify。

## Alternatives

1. 仅全量快照：无法恢复到中间时刻；
2. 数据库级 binlog：侵入 MVCC 语义，违反 additive 原则。

## Consequences

优点：时间点恢复闭环；格式复用既有 CRC/分段经验。

缺点：归档日志随时间增长，需配套保留策略（后续版本）。

风险：重放顺序必须按 commitTS 单调，测试覆盖乱序与重复重放。

## Implementation

代码影响范围：

- `backup/pitr/`（PitrWriteLog / WALArchiveManager / CheckpointManager /
  RestoreTimeline）；
- `PitrRestoreTest` 与 `docs/backup/pitr-guide.md`。
