# Phase 65 Review — P1d v4 Module Enhancement

## 总体结论

Optimization Roadmap P1d 完成：HNSW 多层图检索（ADR-0332）与跨集群
复制流水线增强（ADR-0333）。全量回归 **14742 tests / 0 failures /
6 skipped**（本地），真实 Runner 门禁 6/6 全绿（main/develop 各
build/test/transaction-e2e）。

## 交付清单

1. **HNSW 多层图检索**（ADR-0332）：`HnswIndex` 从"分层列表 + 全量
   扫描"原型重写为多层图——splitmix64(id) 确定性随机层级、逐层贪心
   连接（双向边）、邻居超限按距离裁剪、入口节点逐层下降 + 层 0
   efSearch 候选扩展；20K×64 检索 P50 0.473ms / P99 0.847ms
   （目标 <1ms），召回率 recall@10 ≥0.9；带版本序列化（参数 + 向量 +
   层邻居边 + 入口节点）；小索引（≤256）暴力退化避免近似误差；
   全零向量与 VectorStore 语义对齐。
2. **复制流水线增强**（ADR-0333）：
   - `ReplicationEventCodec` 批量帧（标记字节 + 计数 + 长度前缀事件
     + 批量 CRC，单事件帧不冲突）；
   - `CrossClusterReplicationChannel.sendBatch`（一次 REPLICATION
     RPC 多事件）/ `sendAsync` / `sendBatchAsync`（不等待响应，
     LongAdder 成功/失败计数）；远端注册的 consumer 自动识别批量帧
     并按序拆分应用，单事件兼容；
   - `CrossClusterWatermark.startPeriodicCheckpoint`（后台 daemon
     定时刷盘，close 兜底）；
   - `ConflictResolver` 接口 + `LwwConflictResolver` 实现，
     `CrossClusterSink` 依赖接口（CRDT 后续接入不改调用方）。

## 测试与门禁

- 新增测试 19 项：HNSW 图结构/召回率/序列化/确定性/去重/零向量
  （8）+ HNSW 基准回归护栏（1）+ 批量编解码（5）+ 通道批量/异步
  metrics（3）+ 水位周期刷盘（3）+ 冲突策略接口（3，其中含 sink
  兼容性）；既有 HNSW/复制 E2E/混沌/基准全部保持通过；
- 修复 Raft 时序竞态：`LeaderTransferTest.transferLeadershipRejects-
  LaggingTarget` 仅等待目标日志追平，未等待 leader 侧 matchIndex 确认，
  偶发交接误拒；改为确定性双条件等待（`matchIndex >= lastLogIndex`，
  与 transferLeadership 前置条件一致），`lastLogIndex()` 提升为公开
  可观测性 API，10/10 稳定；
- 基准接入：`HnswSearchBenchmarkTest`（P99 <5ms 回归护栏）进入
  `scripts/benchmark.sh`（core/full）与 release 门禁显式清单。

## 性能基线（本地，2026-08-15）

| 指标 | 结果 | 目标 |
| --- | --- | --- |
| 20K×64 检索 P50 | 0.473ms | <1ms |
| 20K×64 检索 P99 | 0.847ms | <1ms |
| 召回率 recall@10（2K 随机 64 维） | ≥0.9 | ≥0.9 |
| 旧暴力实现 P99 | ≈9.9ms | — |

## 已知限制（如实记录）

- HNSW 为近似检索（召回 <100%）；参数 efConstruction/efSearch 影响
  召回/延迟平衡，基准矩阵已记录；
- 批量帧失败语义为"逐事件水位保证可恢复"（批量粒度 ack），异步 ack
  下错误检测滞后由 metrics 补偿；
- 复制通道未接入真实跨集群网络延迟/断链混沌（现有 chaos 为进程内
  RPC 口径），列入 P3。

## 后续

- P1 归档完成：TD-002/007/020/021 关闭（P1c 产物随本次归档补记）；
- 按 optimization-roadmap 进入 P2 功能深度（BIT/GEO/JSON 路径/时序
  聚合/向量多集合/跨集群 2PC）。
