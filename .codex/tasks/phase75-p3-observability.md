# Phase 75 — P3：可观测性收口（Vector/Replication/Multi-Model/Backup Metrics）

## Context

P3 第三项。基线：ADR-0070 MetricsExporter/ProductionInfo 只覆盖
Cluster/Txn/MVCC；向量、复制、多模型、备份在 INFO 与 Prometheus
均无指标；默认 INFO 只有 `# Server`；无 Prometheus 文本端点。

## Goal

1. ADR-0344 已批准（本阶段先出 ADR）
2. observability 包 4 个注册表 + ObservabilityRegistry 聚合
3. INFO sections：vector/replication/multimodel/backup
4. MetricsExporter.exportAll + `/metrics/prometheus` 端点（RBAC）
5. 向量/备份生产喂数（additive，不破坏既有调用）
6. TDD：注册表/渲染/端点/INFO section 单测 + 全量回归 + 真实 Runner
7. 复制/多模型喂数与 OTel 记录为 Phase 增量

## 交付

| 模块 | 文件 |
| --- | --- |
| 注册表 | observability/VectorMetricsRegistry 等 4 个 |
| 聚合 | observability/ObservabilityRegistry |
| 渲染 | cluster/metrics/MetricsExporter（exportAll） |
| 端点 | console/rest/ConsoleRestServer（/metrics/prometheus） |
| 接线 | Main、VectorIndexSyncStorageEngine、BackupManager/RestoreManager |
| 文档 | ADR-0344、roadmap、CHANGELOG、本任务 |

## Test Plan

- ObservabilityRegistryTest：喂数 → INFO sections / Prometheus 文本断言
- MetricsExporter 扩展测试（原 export 回归不破坏）
- ConsoleRestServer prometheus 端点：鉴权 200/无 token 403、文本格式
- InfoCommand sections 注册测试（INFO vector/replication/...）
- 全量回归 0 failures + 真实 Runner 门禁

## 验收

- ADR-0344 已批准；Conventional Commit 拆分
- `INFO vector/replication/multimodel/backup` 返回聚合文本
- `/metrics/prometheus` 输出 Prometheus 文本且经 token RBAC
- 全量回归 0 failures；真实 Runner 门禁通过

## 状态

✅ 完成（2026-08-15）：ADR-0344 → TDD（12 项新测试）→ 全量回归
14897 tests / 0 failures / 11 skipped（CiTransactionE2EParameterizedTest
端口占用环境竞态单独重跑 41/41 通过）→ 真实 Runner 门禁 main
build/test/transaction-e2e 3/3 全绿。

交付：observability 包 4 注册表 + ObservabilityRegistry（INFO sections
vector/replication/multimodel/backup）、MetricsExporter.exportAll、
`/metrics/prometheus` 端点（token RBAC）、向量/备份生产喂数
（additive 构造，不破坏既有调用）。

Phase 增量（已记录）：复制管线喂数、多模型命令喂数、OTel span。

## Phase 增量（ADR-0345，本阶段）

1. 复制喂数：ReplicationPipeline/BidirectionalPipeline additive
   构造注入 ReplicationMetricsRegistry（attach LagTracker + 计数）；
2. 多模型命令喂数：JsonCommand/MultiModelCommand/TimeSeriesCommand
   additive 构造 + CommandRegistry 新重载（JSON/TS/字节计数）；
3. W3C traceparent：Tracer.startW3c/injectTraceparent/extractTraceparent
   （零依赖 OTel 兼容）+ TracingMetricsRegistry + INFO tracing section +
   GatewayRuntime 命令 span（同步路径）。

验收：全量回归 0 failures；真实 Runner 门禁通过。

## 状态（增量）

✅ Phase 增量完成（2026-08-16）：复制喂数（ReplicationPipeline/
BidirectionalPipeline + ReplicationMetricsFeedingTest）、多模型命令喂数
（Json/MultiModel/TimeSeriesCommand + CommandRegistry 新重载 +
MultiModelCommandMetricsTest）、W3C traceparent（Tracer W3C +
TracingMetricsRegistry + INFO tracing section + GatewayRuntime 命令
span，TracerTest +3 / ObservabilityRegistryTest +2）。全量回归
14907 tests / 0 failures / 11 skipped；真实 Runner 门禁待确认。
