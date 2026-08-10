# ADR-0063: Parallel Region Migration

## Status

Accepted

## Context

TD-033：100B 迁移 82.7MB/s（目标 >150MB/s）。单 worker 流式迁移受
每条目固定开销限制，需要多 worker 并行迁移。

## Decision

- `RegionTransferManager`：键范围按 `transfer.worker.pool`
  （size=min(8, CPU)）切分为 `MigrationChunk(startKey, endKey,
  checksum, version)`；
- 每个 chunk 独立 worker：扫描 → 零拷贝 applyRawBatch → CRC →
  checkpoint 持久化（chunk 粒度）；
- 能力：retry（失败 chunk 重试）、pause/resume（按 chunk 暂停/
  恢复）、并发多 worker；
- 顺序保证：chunk 间键不重叠，版本屏障全局一致；
- 完成条件：全部 chunk DONE → 路由切换。

## Alternatives

1. 单 worker 流式：固定开销不可摊薄，否决。
2. 无 checkpoint 并行：崩溃后全量重来，否决。
3. 按段（256 段）并行：段间键不连续，chunk 边界与路由不匹配，否决。

## Consequences

优点：吞吐随 worker 数近似线性提升；chunk 级 checkpoint 恢复；
CRC 保证完整性。

缺点：并发写目标存储需分段锁支持（applyRawBatch 已支持）；
worker 间共享源迭代器需快照隔离（各 chunk 独立扫描快照）。

风险：worker 数超过核数导致收益递减（默认 min(8,CPU)）。

## Implementation

- `cluster/migration/parallel/`（RegionTransferManager / MigrationChunk /
  ChunkWorker / ChunkCheckpoint）
- 测试：MigrationParallelTest（≥20）+ 基准（目标 >150MB/s）。
