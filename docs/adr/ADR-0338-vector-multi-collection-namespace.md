# ADR-0338: Vector Multi-Collection Namespace

## Status

Accepted

## Context

M1 向量能力为单一 `VectorIndexStore`（VECTOR.ADD/SEARCH/DEL/LEN），
无集合隔离；checkpoint 手动触发；SQL 混合检索绑定单一 VectorStore。
P2 需要多集合命名空间、自动 checkpoint 与集合感知的 SQL 检索。

## Decision

- 新增 `VectorCollectionRegistry`：`ConcurrentHashMap<collection,
  VectorIndexStore>`；`put/delete/collection/drop/names` 操作，
  写入/删除标记 dirty；`checkpoint(collection, dir)` 与
  `checkpointAll(dir)` 原子落盘（`<collection>.tvif`，
  VectorIndexFile 既有格式），`loadAll(dir)` 恢复；
  `startAutoCheckpoint(dir, interval)` 后台 daemon 定时刷脏集合，
  `close()` 停调度并兜底全量 checkpoint；
- 命令语法向后兼容：VECTOR.ADD/SEARCH/DEL/LEN 支持可选
  `COLLECTION <name>` 前缀（缺省默认集合）；新增
  `VECTOR.LIST`（[名称, 数量] 排序数组）、`VECTOR.DROP <name>`、
  `VECTOR.CHECKPOINT [name]`（需配置 checkpoint 目录）；
  VECTOR.ADD 自动创建集合；搜索不存在集合返回空数组；
- SQL 混合检索：`VectorSqlSearch` 增加集合感知重载
  （`bindCollection(table.column, collection)` + 从注册表解析
  VectorStore），既有单存储 API 不变；
- `CommandRegistry.createDefaultWithVector` 内部以默认集合包装
  既有 VectorIndexStore（构造签名不变），并注册 vector.list/drop/
  checkpoint。

## Alternatives

1. 每集合独立注册表构造：调用方需感知多 store，破坏既有接线；
2. 命令强制集合参数：破坏 VECTOR.ADD/SEARCH 既有语法。

## Consequences

优点：向后兼容、集合隔离 + 自动持久化、SQL 检索集合化。

缺点：checkpoint 目录级全局配置；集合粒度为内存索引（未做磁盘
LRU 卸载）。

风险：自动 checkpoint 与并发写竞争——checkpoint 快照在 dirty 标记
后串行执行，close 兜底（与水位周期模式一致）。

## Implementation

`vector/collection/VectorCollectionRegistry.java`、
`command/VectorCommand.java`（集合前缀 + LIST/DROP/CHECKPOINT）、
`vector/sql/VectorSqlSearch.java`（集合重载）+ 测试。
