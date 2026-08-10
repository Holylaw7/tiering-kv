# ADR-0016: Crash Recovery Strategy

## Status

Accepted

## Context

进程崩溃后内存态丢失，必须从 WAL 重建 MemTable。需要处理：正常恢复、
部分写入（截断尾部）、checksum 失败、checkpoint 加速。

## Decision

启动恢复流程：

```text
Open WAL → 按 segment 序号扫描 → 逐条校验 checksum
→ 重放有效记录 → 重建 MemTable → Open Service
```

1. **正常恢复**：全部记录校验通过，逐条重放（PUT/DELETE），最终状态一致；
2. **部分写入**：尾部记录不足定长头或 payload 时，判定为崩溃残尾，
   丢弃并**截断文件**至最后有效偏移；
3. **checksum 失败**：停止该 segment 及后续所有 segment 的重放
   （无法信任顺序），截断坏 segment 尾部；
4. **TTL 语义**：PUT 恢复时按 `TIMESTAMP + TTL` 计算绝对过期点，已过期的
   键不恢复（避免宕机期间过期键复活）；
5. **Checkpoint 加速**：若存在 checkpoint（MemTable 快照 + WAL offset），
   先载入快照，再从 offset 重放剩余 WAL；checkpoint 缺失/损坏则全量重放；
6. **删除记录**：DELETE（用户/淘汰）必须落 WAL，防止崩溃后已删键复活。

## Alternatives

1. 仅全量重放：正确但随 WAL 增长恢复变慢（checkpoint 解决）；
2. 恢复期拒绝写入：简单但停机时间=恢复时间；
3. 并行重放：多段并行可加速，但破坏顺序语义，Phase 7 评估。

## Consequences

**优点：** 启动一致性可证明；损坏尾部自愈；checkpoint 缩短恢复窗口。
**缺点：** 恢复为单线程顺序扫描；截断操作需谨慎（仅尾部）。
**风险：** 中段损坏导致后续数据不可用 → 记录在案，运维可手工处理；
checkpoint 与并发写入竞态 → 先记 offset、后快照、再重放 offset 之后。

## Implementation

- `RecoveryManager`（扫描/校验/重放/截断）、`WALReader`；
- `CheckpointManager`（快照 + offset）；
- `WALManager.recover / checkpoint`；Main 启动时先恢复后监听。
