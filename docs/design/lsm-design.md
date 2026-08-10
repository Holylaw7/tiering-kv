# LSM-Tree 详细设计（LSM Design）

状态：✅ 已实现（Phase 5，ADR-0017 / 0018 / 0019）

## 1. 分层

```text
WAL（追加日志）→ MemTable（热层）→ Flush → SSTable（冷层）
  → Manifest + Compaction
```

## 2. 写路径

- SET：WAL append → MemTable（Phase 4）；
- Flush：快照 → SSTableWriter → 版本守卫移除内存 → WAL checkpoint；
- 迁移：EvictionManager → ColdMigration → pending 缓冲 → 阈值落 SSTable。

## 3. SSTable（ADR-0018）

Data Blocks（默认 4KB，CRC32C）→ Index Block（firstKey/offset/size）→
Bloom Block（bits/key=10）→ Footer（magic/version/offset/checksum）。

## 4. 读取

```text
pending → 新表 → 旧表：Bloom → Index 二分 → Block 解码 → 块内二分
```

## 5. Compaction（ADR-0019）

- 触发：表数 ≥ 8；
- 全量合并：最新表胜出；tombstone 移除键；过期 TTL 丢弃；
- 输出新表 + 删除输入 + 原子更新 Manifest。

## 6. Manifest

`manifest.bin`：表元数据（id / entryCount / size / firstKey / lastKey），
顺序即创建序（新表在后）。

## 7. 一致性

- Flush 用版本守卫：快照版本与当前版本一致才从内存移除（并发新写保留）；
- WAL checkpoint 在 flush 后推进，恢复 = 快照 + 重放剩余 WAL + 冷层 Manifest；
- 冷层损坏：块 CRC 失败抛 ColdCorruptionException，由读取路径上报。

## 8. 已知限制

- 全量合并写放大（Phase 7 leveled）；
- pending 缓冲未落盘前崩溃会丢失该批迁移数据（Phase 6 持久化 pending）；
- 读取按需开表（reader 缓存），未做 mmap（Phase 8）。
