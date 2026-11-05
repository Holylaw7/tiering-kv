# ADR-0362：INFO 增加 `# Tiering` 段落（只读存储分层指标）

- 状态：Accepted
- 日期：2026-11-05
- 关联：TD-095、ADR-0355（INFO 标准 section）

## 背景

控制台的"运行总览"此前只能看到 QPS、延迟、内存总量等通用指标，
无法体现 Tiering-KV 真正的差异点：**MemTable / SSTable 分层、Flush、迁移、BlockCache**。

内核其实已经在 `StorageMetrics` / `CacheStatistics` 中维护了这些计数，
但 `INFO` 只渲染 server / clients / memory / stats / keyspace 五个段落，
控制台拿不到数据，只能展示"通用 Redis 后台"的指标。

## 决策

在 `INFO` 末尾追加一个 **只读** 段落 `# Tiering`，字段全部来自既有指标对象：

| 字段 | 来源 |
| --- | --- |
| `tiering_memtable_used_bytes` / `_max_bytes` / `_entries` | `StorageMetrics.setMemoryGauges` |
| `tiering_cold_sstable_count` / `tiering_cold_disk_usage_bytes` | `StorageMetrics.setColdGauges` |
| `tiering_flush_count` / `tiering_flush_bytes` | `StorageMetrics.recordFlush` |
| `tiering_migration_pending` / `_success` / `_failed` | `StorageMetrics` 迁移计数 |
| `tiering_block_cache_hits` / `_misses` / `_evictions` / `_hit_rate` | `CacheStatistics.snapshot()` |
| `tiering_cold_share` | 由冷层磁盘/总量计算（仅此一项是派生值，公式写进字段名） |

为了让"空闲实例"也显示真实值，读取该段落前会调用
`TieringController.refreshGauges()` 刷新内存/冷层 gauge，
保证 MemTable 与 SSTable 数据不依赖"最后一次写入"的时间点。

未接线 `StorageMetrics` 时（单测、嵌入式用法）**不输出该段落**，
避免出现一堆恒为 0 的假指标。

## 备选方案

| 方案 | 否决原因 |
| --- | --- |
| 控制台侧统计 | 控制台看不到 SSTable 文件与 BlockCache，无法统计 |
| 新增独立命令 `TIERING STATS` | 需要改命令表与协议文档，收益与 INFO 段落相同但成本更高 |
| 用 `CONFIG GET` 暴露 | CONFIG 是配置语义，不该承载运行时计数 |

## 影响

- `INFO` 变长（新增约 15 行），Redis 客户端解析不受影响（按段落/键值解析）。
- 控制面 `LiveMetrics` 增加 15 个字段；`docs/api/openapi.json` 与前端类型同步更新。
- 内核全量回归仍需通过（`ServerInfoProviderTest` 新增 2 例）。

## 实现

- `observability/ServerInfoProvider.java`：新增带 `StorageMetrics`/`CacheStatistics` 的构造与 `tiering()`
- `storage/tiering/TieringController.java`：抽出 `refreshGauges()`
- `Main.java`：接线 `tiering.metrics()`、`blockCache.statistics()::snapshot`、`tiering::refreshGauges`
- 控制面：`LiveMetrics` + `MonitorService.live()` 解析新段落
- 前端：运行总览"存储分层"流水线（MemTable → Flush → SSTable → BlockCache）
