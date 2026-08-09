# 存储架构（Storage Architecture）

状态：基线（细化见 ADR-0002、ADR-0005 与 design/）

## 1. 分层视图

```text
Memory Tier (MemTable)
  ├── 分段哈希（点读写） + 跳表（有序/范围）
  │
  ▼
Hotness Manager（LFU/ARC 采样判定）
  │
  ▼
Cold Storage（StorageEngine SPI）
  ├── Bitcask：追加日志 + 内存索引 + merge
  └── LSM-Tree：MemTable → SSTable → Compaction（+ Bloom Filter）
```

## 2. 组件职责

| 组件 | 职责 | 阶段 |
| --- | --- | --- |
| memtable | 热层主数据结构（分段哈希；skiplist 支持有序访问） | Phase 2 |
| wal | 写前日志，崩溃恢复 | Phase 4 |
| bitcask | 追加写、顺序读、后台 merge | Phase 4 |
| lsm | 层级 SSTable、压缩、Bloom Filter | Phase 5 |
| compaction | 空间回收与层级整理 | Phase 4/5 |
| bloom | 冷读过滤，防不存在键穿透 | Phase 5 |

## 3. 关键路径

**写：** `Command → WAL（先落盘）→ MemTable →（异步）→ Cold Storage`

**读：** `MemTable 命中 → 返回`；未命中 → `Bloom Filter → Cold Storage → 升热`

**迁移：** `Hotness Manager 采样 → Scheduler 异步迁移 → 索引更新`

## 4. 一致性原则

- 写入先落 WAL，再更新内存/冷存储；
- 迁移期间使用版本号机制保证"读旧写新"一致（Phase 6 细化）；
- 引擎切换由 `storage.engine` 配置驱动，上层无感知。
