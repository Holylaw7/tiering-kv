# ADR-0204: Production LSM Evolution

## Status

Accepted

## Context

Phase 5 起 Compaction 为 size-tiered 全量合并（读放大）；MemTable 为
快照式 Flush（写停顿）。需要 leveled compaction + Immutable 轮转。

## Decision

1. `storage/compaction/LeveledCompactionPlanner`：L0→L1→L2 层级计划
   （大小/层数阈值）；
2. `storage/memory/ImmutableMemTableRotator`：Active → Immutable →
   Flush 轮转；
3. 与 Compaction / FlushScheduler 联动（零回退，保持格式兼容）；
4. 验收：层级计划矩阵 + 轮转矩阵 + 兼容性。

## Alternatives

1. 保持 size-tiered：读放大持续；
2. 直接重写存储：破坏兼容。

## Consequences

优点：读放大降低，Flush 不停顿。

缺点：实现复杂度上升。

风险：兼容性由零回退测试兜底。

## Implementation

代码影响范围：`storage/compaction/` + `storage/memory/` + 测试 +
`docs/storage/leveled-lsm.md`。
