# Task: Phase 2 — 内存核心与存储引擎

状态：⏳ 未开始

## 目标

实现 MemTable（分段哈希 / 跳表）、WAL，并演进 Bitcask / LSM 冷存储。

## 交付物

- storage/memory：memtable、skiplist；
- storage/wal：预写日志与崩溃恢复；
- storage/cold/bitcask：追加日志 + 索引 + merge；
- storage/cold/lsm：SSTable + compaction（后续阶段）；
- 单元 + 集成 + 恢复测试；
- docs/design/{memory,bitcask,lsm}-design.md 细化。

## 验收

- 重启后 WAL 回放恢复数据；
- Bitcask 读写与 merge 正确；
- LSM 读写与 Bloom Filter 生效。

## 关联

- ADR-0002、ADR-0005。
