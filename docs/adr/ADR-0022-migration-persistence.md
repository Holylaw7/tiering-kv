# ADR-0022: Pending Migration Persistence Strategy

## Status

Accepted

## Context

异步迁移队列在进程崩溃时会丢失未完成任务（数据仍在内存 + WAL，未写入冷层）。
需要持久化迁移状态。候选：

- **WAL Extension**：在存储 WAL 增加 MOVE 记录——与存储日志耦合，重放时需区分
  命令与迁移，复杂度高；
- **Migration Manifest**：单一快照文件（任务表）——每次状态变更全量重写，
  高频迁移下写放大；
- **Standalone Log**：独立 `migration/migration.log` 追加记录——与存储 WAL
  解耦，状态变更仅追加，恢复时扫描。

## Decision

采用 **Standalone Migration Log**：

```text
migration/migration.log
  [MAGIC "TKMG"][VERSION 1][STATUS 1][KEY_LEN 4][KEY]
  [VERSION 8][RETRY 4][TARGET_LEN 4][TARGET][CRC32C 8]
```

1. 记录类型 = 任务生命周期事件：PENDING → RUNNING → SUCCESS / RETRY → FAILED；
2. 每次状态变更追加一条记录（CRC 校验）；
3. **启动恢复**：扫描日志，按 key+version 取最新状态；PENDING/RUNNING/RETRY
   视为未完成 → 重新入队执行；
4. **幂等重放**：重跑 = cold.put（同版本覆盖）+ WAL DELETE + 版本守卫删内存，
   重复执行安全；
5. **日志压缩**：恢复完成后重写日志，仅保留未完成任务；
6. 迁移成功/失败均以 SUCCESS/FAILED 落盘，防止崩溃后重复迁移。

## Alternatives

1. WAL Extension：耦合且重放语义复杂，被否决；
2. Manifest：写放大，被否决；
3. 不持久化：崩溃丢 pending（Phase 5 已知限制），本阶段解决。

## Consequences

**优点：** 与存储解耦、追加写高效、恢复幂等、可压缩。
**缺点：** 多一个日志文件；恢复需扫描。
**风险：** 日志损坏 → CRC 校验失败时停止该日志后续记录（与 WAL 恢复同策略）。

## Implementation

- `MigrationLog`（append/recover/compact）、`MigrationTask`（状态机）；
- `MigrationScheduler` 恢复入口；`TieringController.recover()` 启动调用。
