# ADR-0111: PITR Retention and Archive Lifecycle

## Status

Accepted

## Context

PITR 归档（ADR-0104）无保留策略，日志持续增长。需要按时间/数量清理，
同时保证不低于最新 checkpoint 的恢复点完整。

## Decision

新增 `backup/pitr/`：

1. `RetentionPolicy`：maxSegments / maxAgeMillis / minSafeWatermark；
2. `ArchiveLifecycleManager`：扫描段、计算安全删除水位（不得越过
   最新 checkpoint watermark），删除过期段；
3. 删除后恢复语义不变：最新 checkpoint + 剩余归档仍可 PITR。

## Alternatives

1. 不清理：磁盘无限增长；
2. 粗暴删除：破坏恢复点。

## Consequences

优点：归档可管理，恢复点有保证。

缺点：早于保留窗口的时间点不可恢复（预期行为，需文档化）。

风险：并发 checkpoint 与清理需原子水位读取。

## Implementation

代码影响范围：`backup/pitr/RetentionPolicy` + `ArchiveLifecycleManager`
+ 测试 + `docs/backup/pitr-retention.md`。
