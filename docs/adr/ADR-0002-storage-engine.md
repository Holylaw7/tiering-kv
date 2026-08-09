# ADR-0002: Storage Engine Strategy

## Status

Accepted

## Context

Redis 的持久化天然是"内存优先"；而 Tiering-KV 的目标是冷热分层：热数据驻留内存，
冷数据落盘，并将内存占用降低 60%–80%。因此必须选择磁盘存储引擎。

候选引擎需满足：

- 点写（SET）高吞吐、点读（GET）低延迟；
- 崩溃恢复能力（WAL / 日志回放）；
- 支持后台压缩/合并，避免存储无限膨胀；
- 与 MemTable 协同，支撑异步冷热迁移。

## Decision

采用"接口统一、双引擎演进"策略：

1. **定义 `StorageEngine` SPI**：`put / get / delete / iterator / close`，作为冷存储
   唯一入口，上层不感知具体引擎。
2. **第一代引擎：Bitcask**（Phase 4 实现）
   - 写：仅追加日志，顺序 IO；
   - 读：一次内存索引定位 + 一次磁盘随机读；
   - 空间回收：后台 merge（合并有效记录、重写新文件）；
   - 适用：Redis 式点读写工作负载。
3. **第二代引擎：LSM-Tree**（Phase 5 实现）
   - 结构：MemTable → SSTable → Compaction；
   - 支持有序遍历与范围查询；
   - Bloom Filter 降低不存在键的读放大；
   - 后台 compaction 控制层级与空间放大。
4. **WAL 独立成模块**：所有写入先落 WAL 再更新内存/冷存储，保证崩溃一致性；
   具体一致性级别（同步/异步 fsync）在 WAL 实现阶段细化并记录 ADR。
5. **配置驱动**：`storage.engine=bitcask|lsm`，Phase 4 前默认仅内存实现、持久化关闭。

## Alternatives

1. **仅 Bitcask**：简单可靠，但无范围查询，且内存索引随键数线性增长。
2. **仅 LSM-Tree**：范围查询与空间效率好，但实现复杂度高，读写放大需调优，
   不适合作为第一阶段交付。
3. **直接绑定 RocksDB（C++/JNI）**：成熟但违反"从零自研"目标，且 JNI 增加
   部署与维护复杂度。

## Consequences

**优点：**

- 分阶段交付：先用 Bitcask 打通全链路，再用 LSM 演进；
- SPI 隔离使引擎可替换、可 A/B 对比；
- WAL 与引擎解耦，一致性逻辑可独立测试。

**缺点：**

- 需要维护两套引擎的实现与测试成本；
- LSM 的 compaction 调度是后续主要复杂度来源。

**风险：**

- Bitcask 全量索引占用内存过多 → 冷键索引迁移到磁盘/稀疏索引（Phase 8 评估）；
- 写放大拖慢落盘 → 通过 metrics 观测 compaction 放大系数。

## Implementation

- 模块：`storage`、`wal`、`sstable`、`compaction`、`memory`（MemTable）；
- Phase 4：`BitcaskEngine` + WAL + merge；
- Phase 5：`LSMEngine` + SSTable + compaction + Bloom Filter；
- 配置项：`config/tiering-kv.yaml`（Phase 3 详细设计）。
