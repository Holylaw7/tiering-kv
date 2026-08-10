# ADR-0080: Persistent MVCC Index

## Status

Accepted

## Context

`MvccStorageEngine` 的内存版本索引仅靠启动时全量扫描底层存储重建。
数据量大时启动慢，且快照恢复只恢复最新值路径无法保留历史版本
（Phase 19 限制）。

## Decision

引入持久化 MVCC 索引：

- `MvccIndexSnapshot`：userKey 版本链的内存快照；
- `MvccIndexWriter`：写入快照文件，格式
  `MAGIC + VERSION + COUNT + (KEY_LEN + USER_KEY + VALUE_LEN + VALUE +
  START_TS + COMMIT_TS + TYPE) + CRC32`；VALUE 必须持久化，否则恢复后
  索引无法直接提供读取（快照恢复后读取路径依赖索引中的值）；
- `MvccIndexReader`：校验 MAGIC/VERSION/CRC 并加载；
- `PersistentMvccIndex`：save/load/restore，支持：
  - 启动恢复：load snapshot → 底层 WAL replay → 增量重建索引；
  - 快照恢复：restore 后版本链完整。

写路径仍然同步维护内存索引；持久化索引仅用于加速恢复与归档，
不参与在线读写（避免双写一致性复杂度）。

## Alternatives

1. 直接持久化底层存储（已有 WAL/SSTable）：索引仍需重建。
2. 索引双写（WAL 同步写索引文件）：在线路径增加 IO。
3. 仅靠启动扫描：大数据量启动慢。

## Consequences

优点：

- 恢复 O(版本数) 而不是全量扫描重建；
- 快照恢复保留历史版本。

缺点：

- 快照文件与存储可能不一致（由 CRC + WAL replay 兜底）；
- 需要显式 save 触发，非每次提交写入。

风险：

- 低；CRC 校验失败回退全量重建。

## Implementation

- `src/main/java/io/tieringkv/mvcc/index/`：MvccIndexSnapshot /
  MvccIndexWriter / MvccIndexReader / PersistentMvccIndex；
- `MvccStorageEngine.importIndex`（从快照构建版本链）；
- 测试：快照 round-trip、CRC 损坏、重启恢复、增量重建。
