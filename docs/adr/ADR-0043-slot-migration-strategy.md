# ADR-0043: Slot Migration Strategy

## Status

Accepted

## Context

Phase 11 的 slot→shard 映射在启动时静态分配，节点扩缩容无法在线调整。
生产系统需要在节点加入/退出时把部分 slot 的数据从旧 shard 搬到新
shard，并保证不丢数据、可断点续传。

## Problem

- 数据迁移必须无数据丢失：先复制、校验，再切换流量、删除源；
- 迁移可能中断（进程崩溃/网络抖动），需要 checkpoint 可恢复；
- 需要校验迁移后的数据完整性（checksum）；
- 需要明确的流量切换时机，避免读写期间数据不一致。

## Options

1. **停服迁移**：简单但牺牲可用性，被否决；
2. **Redis Cluster 式在线迁移**：源/目标双写 + 增量 + 切换（选定）；
3. **全量重放日志**：依赖完整 Raft 日志，成本高且与快照冲突。

## Decision

采用状态机化在线迁移：

```text
INIT → COPYING → VERIFYING → SWITCHING → DONE
  └────────── 失败回滚 / checkpoint 续传 ──────────┘
```

1. `SlotMigrationManager` 负责编排；`MigrationTask` 描述
   `(slotRange, source, target, leader)`；
2. `COPYING`：按 key 有序复制源分片数据到目标，每批记录
   `MigrationCheckpoint`（最后复制 key + 已复制字节数 + 校验和累积）；
3. `VERIFYING`：对迁移数据计算 CRC32C 与源对比；
4. `SWITCHING`：更新 `SlotTable` 映射 + 元数据 leader 归属（写路径
   原子切换），源数据仅标记删除；
5. `DONE`：清理源数据；
6. 中断恢复：从 checkpoint 继续复制，已完成 key 幂等跳过；
7. 失败：回滚到迁移前状态（切换未发生则无副作用）。

## Consequences

**优点：** 在线迁移、可恢复、可校验、无数据丢失；
**缺点：** 双写/复制期间的增量写入需源目标双写或增量同步，原型采用
"切换后只写目标 + 切换前存量复制"的简单模型；
**风险：** 切换窗口内新写入归属变化 → 切换在锁内原子完成并校验。

## Future Evolution

- 增量迁移（日志订阅/双写）；
- 多 slot 并行迁移与限速；
- 迁移与 Raft membership change 联动。
