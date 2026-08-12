# ADR-0207: Leveled Compaction Execution

## Status

Accepted

## Context

Phase 41 的 leveled 为计划器原型（TD-077）；需要接入实际 Compaction
执行（合并 + tombstone + TTL 清理 + 层级落盘）。

## Decision

1. `storage/compaction/LeveledCompactionExecutor`：计划 → 实际合并
   （latest wins + tombstone + TTL 清理）→ 层级文件落盘；
2. 与 LeveledCompactionPlanner / Compaction 联动；
3. 零回退：SSTable 格式兼容；
4. 验收：执行矩阵 + 层级落盘 + 兼容性。

## Alternatives

1. 仅计划：不降低读放大；
2. 重写存储：破坏兼容。

## Consequences

优点：leveled 实际生效，读放大降低。

缺点：实现复杂度上升。

风险：兼容性由零回退测试兜底。

## Implementation

代码影响范围：`storage/compaction/` + 测试 +
`docs/storage/leveled-execution.md`。
