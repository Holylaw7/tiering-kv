# ADR-0344: Observability Consolidation (Vector / Replication / Multi-Model / Backup Metrics)

## Status

Accepted

## Context

P3 第三项：可观测性收口。现状：

- ADR-0070 已建立 `MetricsExporter`（Prometheus 文本）与
  `ProductionInfo`（INFO CLUSTER），覆盖 Region / Raft / Migration /
  Gateway / Transaction / MVCC；
- 默认 `INFO` 仅输出 `# Server`（`MetricsRegistry.infoText`），
  `CommandRegistry` 已支持扩展 sections，但 `Main` 传空 map；
- 向量（ADR-0319/0320/0332/0338）、复制（ADR-0108/0114/0135）、
  多模型值（JSON/TS/向量）、备份恢复（ADR-0097/PITR）**在 INFO 与
  Prometheus 均无指标**；
- `MetricsExporter` 与 `ProductionInfo` 两处渲染，存在口径漂移风险；
- 无 Prometheus 文本 HTTP 端点（`ConsoleRestServer /metrics` 返回
  JSON，非 Prometheus 格式）。

## Decision

### 1. 新增轻量指标注册表（`io.tieringkv.observability`）

每个注册表只做**聚合 + 快照**，不重复埋点；数据源仍是各模块的
唯一事实（`VectorIndexStore.size()`、`LagTracker`、`AtomicLong`）。

- `VectorMetricsRegistry`：直接引用 `VectorIndexStore`（向量数/维度/
  maxLevel）+ 写/删计数（由 `VectorIndexSyncStorageEngine` 喂入）；
- `ReplicationMetricsRegistry`：LagTracker 水位（副本数/最大滞后 ms）
  + replicated/suppressed/conflicts 计数（本期提供 record API，
  管线喂数接入列为 Phase 增量）；
- `MultiModelMetricsRegistry`：JSON 写入/校验失败、TS 写入、
  多模型字节计数（本期提供 record API，命令喂数接入列为增量）；
- `BackupMetricsRegistry`：备份/恢复次数与字节、PITR watermark
  （`BackupManager`/`RestoreManager` additive 重载喂入）。

### 2. 统一聚合与渲染（单一数据源）

- `ObservabilityRegistry`：持有上述 4 个注册表；
  - `infoSections()` → `Map<String, Supplier<String>>`：
    `vector` / `replication` / `multimodel` / `backup`，输出
    `# Vector\r\n` 风格（与 `ProductionInfo` 一致）；
  - `prometheusText()` → 复用扩展后的 `MetricsExporter`，保证
    INFO 与 Prometheus 从**同一 snapshot** 渲染，杜绝双口径。
- `MetricsExporter` 新增 `exportAll(...)` 重载（原 export 保留，
  既有 Phase 18-23 测试不受影响）。

### 3. 接线

- `Main`：组装 `ObservabilityRegistry`，sections 传入
  `CommandRegistry.createDefaultWithVector`；`VectorIndexSyncStorageEngine`
  增加 additive 构造（可选 registry），向量写/删喂数；
- `ConsoleRestServer` 新增 `GET /metrics/prometheus`（复用现有 token
  RBAC），返回 Prometheus 文本；
- 备份：`BackupManager.backup` / `RestoreManager.restore*` 增加
  additive 重载（可选 registry），Main/运维入口喂数。

### 4. 本期边界（增量明确）

- 本期完成：4 个注册表 + 聚合器 + INFO sections + Prometheus 端点 +
  向量/备份喂数 + 全部单测；
- Phase 增量：复制管线喂数（ReplicationPipeline/BidirectionalPipeline
  注入）、多模型命令喂数（JsonCommand/TimeSeriesCommand/MultiModelCommand）、
  OTel span 透传（预留 `ObservabilityRegistry` 扩展点，不引入 SDK）。

## Alternatives

1. 各模块内嵌 Prometheus 埋点（静态调用 MetricsExporter）：耦合、
   难测试、双口径；
2. 引入 Micrometer / OTel SDK：依赖重、需 agent/绑定，当前无必要；
3. 只加 INFO 不加 Prometheus 端点：无法对接采集器，端点不完整；
4. 每模块各自 HTTP 端点：碎片化、鉴权分散。

## Consequences

优点：

- 单一渲染入口，INFO 与 Prometheus 口径一致；
- 向量/复制/多模型/备份可观测性补齐（框架先行，喂数增量接入）；
- `/metrics/prometheus` 复用现有 RBAC，最小侵入。

缺点：

- 新增 5 个类 + 1 个聚合器；复制/多模型喂数需后续阶段；
- `VectorMetricsRegistry` 不含索引字节（避免引入文件 IO 统计）。

风险：

- 引用外部对象需快照只读（ConcurrentHashMap/LongAdder/volatile）；
- 若后续引入 OTel，需在聚合器加适配层（本期已预留扩展点）。

## Implementation

新增 `io.tieringkv.observability` 包（VectorMetricsRegistry、
ReplicationMetricsRegistry、MultiModelMetricsRegistry、
BackupMetricsRegistry、ObservabilityRegistry）；修改
`cluster/metrics/MetricsExporter`（exportAll）、
`console/rest/ConsoleRestServer`（/metrics/prometheus）、
`Main`（组装 + sections + REST 接线）、`storage/VectorIndexSyncStorageEngine`
（additive registry）、`backup/BackupManager`/`RestoreManager`
（additive 重载）；新增对应单测与 INFO section 测试。
