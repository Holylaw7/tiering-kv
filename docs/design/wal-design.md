# WAL 详细设计（WAL Design）

状态：✅ 已实现（Phase 4，ADR-0014 / 0015 / 0016）

## 1. 架构

```text
Command → StorageEngine → WALStorageEngine（装饰器）
    ├── WALManager（append/flush/rotate/checkpoint）
    │     ├── WALWriter + SegmentManager + LogSegment
    │     ├── RecoveryManager + WALReader（启动恢复）
    │     └── CheckpointManager（快照 + offset）
    └── MemTable
```

## 2. 一致性模型

```text
Append WAL →（fsync/group commit，按 FsyncPolicy）→ Apply MemTable → Return
```

## 3. 记录格式（ADR-0015）

定长头 38B + KEY + VALUE + CRC32C(8B)；PUT/DELETE 两类型；
TTL 存相对时长，恢复按绝对过期点判定。

## 4. Segment（ADR-0014）

- `wal/%06d.log`，默认 64MB 轮转；切换前 force 旧段；
- 序列号单调；恢复按序号升序扫描。

## 5. 写策略

| 策略 | 行为 | 语义 |
| --- | --- | --- |
| ALWAYS | 每次 append 后 force | 严格持久化 |
| EVERY_SEC（默认） | ≤1s 批量 flush+force | 丢失窗口 ≤1s（Redis everysec） |
| NO | 仅写 OS | 测试/性能对比 |

## 6. 恢复（ADR-0016）

- 逐段扫描 → CRC 校验 → 重放 → 截断损坏尾部；
- PUT：`remaining = timestamp + ttl - now`，≤0 跳过；DELETE：tombstone；
- 中段损坏：停止后续重放。

## 7. Checkpoint

- 文件：`wal/checkpoint.bin`（MAGIC + segmentSeq + offset + 快照条目）；
- 顺序：先捕获 WAL offset → 再拍 MemTable 快照 → 落盘（原子替换）；
- 恢复：载入快照（过期键跳过）→ 从 offset 重放剩余 WAL。

## 8. 接入

- 用户 PUT/DELETE：WALStorageEngine 先 append 后应用；
- 淘汰删除：EvictionManager 先 append DELETE 后 removePhysical
  （append 失败则保留数据，防崩溃复活）；
- TTL 主动过期：不落盘（由 PUT.ttl 可推导）；
- WAL 失败：抛 `WalWriteException`，命令层返回 -ERR，不谎报成功。

## 9. 已知限制

- EVERY_SEC 存在 ≤1s 丢失窗口（ALWAYS 可换强一致）；
- 恢复为单线程顺序扫描；
- checkpoint 快照为全量遍历（大内存时成本高，Phase 5/6 优化）。
