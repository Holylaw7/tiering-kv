# Phase 17 评审报告：Region 生命周期与分布式存储完善

Phase 17 · 2026-08-10

## 1. Architecture

```text
Client → ClusterGateway → RegionRouter → RegionManager
                                        → RegionLifecycleService
                                            ├── SplitController
                                            ├── MergeController
                                            ├── LeaderTransferManager
                                            └── PlacementCoordinator(BalanceScheduler)
                                            → Multi-Raft Group → StorageEngine
```

Phase 17 补齐 Region 生命周期闭环：分裂/合并/真实领导权交接/并行迁移/
Redis Cluster 网关/自动均衡计划。

## 2. ADR

| ADR | 决策 | 评价 |
| --- | --- | --- |
| 0061 Region Split Lifecycle | NORMAL→SPLITTING→SPLIT_READY→NORMAL + 五阶段任务 + 写缓冲 | ✅ |
| 0062 Region Merge | PREPARE→LOCK→TRANSFER→UPDATE_META→TOMBSTONE | ✅ |
| 0063 Parallel Region Migration | 按段分片 + chunk 检查点 + 多 worker | ✅（100B 209MB/s） |
| 0064 Real Leader Transfer | TimeoutNow 真实交接 + 日志追平校验 | ✅（24ms） |
| 0065 Placement Auto Balance | 压力检测 + 计划生成 + epoch 保护（不自动执行危险迁移） | ✅ |

## 3. Implementation

- Split：SplitController/RegionSplitTask/SplitSnapshot/SplitWriteBuffer；
- Merge：MergeController/MergeTask（数据零拷贝右→左）；
- Parallel Migration：RegionTransferManager/MigrationChunk/ChunkWorker/
  ChunkCheckpoint + `MemTable.segmentIterator`；
- Leader Transfer：RaftNode.transferLeadership/receiveTimeoutNow +
  TimeoutNow RPC（additive，三类传输实现）+ LeaderTransferManager；
- Redis Gateway：RedisClusterGateway（GET/SET/DEL/MGET/MSET/INFO/
  CLUSTER SLOTS + MOVED）；
- Balance：BalanceScheduler/BalancePlan/RegionMove（region/leader/disk/
  cpu 压力 + epoch 保护）；
- Observability：RaftMetricsRegistry + MigrationMetricsRegistry +
  RegionMetricsRegistry（merge_count 新增）+ INFO RAFT/MIGRATION。

## 4. Tests

新增 156 项（Phase 16 基线 788）：

| 模块 | 新增 | 结果 |
| --- | --- | --- |
| RegionSplitTest | 29 | ✅ |
| RegionMergeTest | 24 | ✅ |
| MigrationParallelTest | 22 | ✅ |
| LeaderTransferTest | 16 | ✅ |
| RedisGatewayTest | 24 | ✅ |
| PlacementBalanceTest | 16 | ✅ |
| Phase17ObservabilityTest | 10 | ✅ |
| RegionChaosTest | 10 | ✅ |
| Phase17RegionBenchmarkTest | 5 | ✅ |

全量回归最终统计见合并后报告（目标 ≥900）。

## 5. Benchmark

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| Split 1M（外推） | ~0.9 s | <10 s | ✅ |
| Merge 1M（外推） | ~0.7 s | <20 s | ✅ |
| 并行迁移 100B | 209.1 MB/s | >150 MB/s | ✅ |
| Leader Transfer | 24 ms | <500 ms | ✅ |
| Gateway GET / SET | 3.68M / 1.67M ops/s | >100K / >50K | ✅ |

## 6. Chaos

- split 期间 10000 并发写无丢失（快照 + 写缓冲分发）；
- merge 期间存储宕机 → 状态重置 + 重启恢复；
- 200ms 延迟 + 10% 丢包下 leader 交接成功；分区下交接优雅失败并可重试；
- Region A 故障不影响 Region B；分裂后旧纪元写入被拒。

## 7. Limitations（不隐藏）

1. split/merge 基准为进程内存储级（未含跨机网络与 Raft 组数据搬迁联动）；
2. CLUSTER SLOTS 基于连续槽位区间（网关层），与 Region 键范围路由
   尚未统一（双路由层并存）；
3. BalanceScheduler 生成计划但不自动执行数据搬迁（设计如此）；
4. Redis 网关未接入真实 TCP 服务（handler 级协议验证）；
5. 200K→1M 为线性外推，非 1M 实测。

## 8. Next Phase

- split/merge 与 Multi-Raft 组数据搬迁联动（子 region 独立 Raft 组）；
- 网关 TCP 服务 + CLUSTER 命令全量（ASK/RESTORE/SLOTSMIGRATING）；
- 路由层统一（Region 键范围 ↔ slot 区间映射）；
- 跨机容器混沌执行（Linux+Docker + tc netem）。

**定位**：Tiering-KV 已具备 Region 生命周期闭环 + 并行迁移 + 真实领导权
交接 + Redis Cluster 网关，Phase 17 全部成功标准达成。
