# ADR-0325: Migration Queue Admission, Batch and Dynamic Workers

## Status

Accepted

## Context

TD-014：迁移为逐任务入队执行（MigrationScheduler + TierWorkerPool
固定线程），缺少批量迁移、队列准入（背压）与 worker 动态扩缩容。

## Decision

- **批量迁移**：`submitBatch(List<MigrationTask>)`——冷层一次
  `writeTable`（单表 flush），WAL DELETE 与内存删除逐条（一致性），
  日志逐条 PENDING/SUCCESS；
- **准入控制**：构造增加 `maxPending`；超出上限拒绝入队（调用方
  背压/重试），`pendingCount()` 可见；
- **动态 worker**：`TierWorkerPool.adjust(workers)`（core/max 联动），
  MigrationScheduler 按 pending 水位调整（高水位扩、低水位缩）。

## Alternatives

1. 无界队列 + 固定线程：实现简单但内存/延迟不可控；
2. 拒绝迁移：数据滞留内存，破坏分层预算。

## Consequences

优点：迁移吞吐提升（批量）、内存可控（准入）、资源自适应。

缺点：批量失败语义（部分成功）需明确——逐任务日志保证可恢复。

风险：worker 抖动（频繁扩缩）——用水位滞回（hysteresis）避免。

## Implementation

`storage/tiering/MigrationScheduler.java`（+batch/admission/adjust）、
`storage/tiering/TierWorkerPool.java`（+adjust）、测试扩展。
