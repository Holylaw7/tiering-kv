# Phase 29 Task Prompt — Distributed Query & Geo Scale Validation

## 1. Context

当前系统已完成：

```text
Phase 1-18 : Storage / Raft / Multi-Raft / Region / Migration / Gateway
Phase 19-23: MVCC / Percolator 2PC / 事务 RPC / 生命周期 / LockResolver / 运行时
Phase 24   : 元数据 Multi-Raft + K8s 清单 + 备份恢复 + 滚动升级 + CI E2E
Phase 25   : 元数据 Multi-Raft 网络化（TD-050 关闭）+ 混沌交付物
Phase 26   : v1 协议冻结 + PITR + CDC + 企业安全 RBAC + Operator + CLI + 发布流水线
Phase 27   : Multi-Region Replication + Geo 事务 + RBAC 接线 + PITR 保留
             + CDC 多消费者组 + SQL/Vector/SaaS 探索原型
Phase 28   : 双向复制 + CRDT + 两地三中心容灾 + SQL 引擎 + HNSW/混合检索
             + SaaS 多租户 + RPC 帧级令牌 + v1.1 发布流水线
```

当前基线：

```text
develop   : dfc0571 merge: integrate Phase28 multi-master replication and advanced query engines
定位      : Enterprise-ready Distributed Database（v1.1.0）
Tests     : 3216/3216 PASS（另 6 项容器门控本地跳过）
新能力    : 双向 CRDT、容灾切换计划、SQL JOIN/聚合、HNSW、SaaS 多租户、RPC 令牌
```

Phase 28 的多主复制、查询引擎与容灾均为进程内原型口径。Phase 29 把它们
推向**分布式与地域规模**：跨 Region 分布式 SQL、向量分片、Geo CRDT
大规模验证、三地五中心与全球一致性读，并完成 v1.2 冻结与跨地域真实
基准。

## 2. Release 前置项（Phase 25–28 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1.0-rc1 → v1.1.0）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO 真实基准 | Phase 27/28 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0/v1.1 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 单向/双向复制路径零回退，新能力 additive；
- 分布式 SQL 与向量分片以基准/召回率为验收，不隐藏失败项。

## 3. Phase 29 Goal

目标：**Distributed Query & Geo Scale Validation**，完成 8 个 Goal：

1. 分布式 SQL 执行（跨 Region JOIN / 数据分片）
2. 向量索引集群化（分片 + 重平衡 + 增量）
3. Geo CRDT 大规模验证与时钟校准
4. 三地五中心容灾与全球一致性读
5. SaaS 计费与市场控制面
6. v1.2 冻结与跨地域生产基准
7. 分布式可观测性与告警
8. 规模化容灾/混沌演练

## 4. Goals

### Goal 1 — 分布式 SQL 执行

目标：SQL 从单机内存执行升级为跨 Region 分布式。

架构：

```text
SqlPlanner → 分片计划（Region 下推）
  ├─ Region A：本地 scan/filter/partial aggregate
  └─ Region B：本地 scan/filter/partial aggregate
              → Coordinator 合并（join/global aggregate）
```

交付：

- `sql/distributed/`：ShardPlanner / PartialAggregate / MergeJoin /
  DistributedExecutor；
- 谓词与 key 范围下推到 Region（复用 UnifiedRouter 路由）；
- 聚合两阶段（partial + merge），JOIN 支持广播/分区策略；
- 验收：跨 Region JOIN 1K×1K 延迟、聚合 100K 行吞吐（如实记录）。

ADR：`ADR-0120 Distributed SQL Execution`。

### Goal 2 — 向量索引集群化

目标：向量索引从单机原型升级为分片集群。

交付：

- `vector/cluster/`：VectorShard（按 id hash 分片）、RebalancePlanner、
  VectorShardManager；
- 增量构建：CDC 向量变更 → 分片索引自动更新；
- 重平衡：分片迁移（复用 Migration 能力）+ 查询路由；
- 验收：分片扩展线性、重平衡期间查询不中断、召回率保持。

ADR：`ADR-0121 Distributed Vector Index`。

### Goal 3 — Geo CRDT 大规模验证与时钟校准

目标：CRDT 收敛性质在规模与真实时钟下验证。

交付：

- `replication/crdt/bench`：百万级并发冲突模拟（多节点 × 多键）；
- `HybridClockCalibrator`：节点间时钟偏差估计与 LWW 校准；
- 收敛审计：任意合并顺序最终一致（性质测试 + 混沌注入）；
- 验收：100 万键冲突收敛、时钟偏差下 LWW 决策可解释。

ADR：`ADR-0122 Geo CRDT Scale Validation`。

### Goal 4 — 三地五中心与全球一致性读

目标：容灾拓扑扩展到五中心，读路径全球一致。

交付：

- `dr/`：DrTopology 扩展（5 中心：2 主 + 2 备 + 1 仲裁）；
- `GlobalReadRouter`：就近读 + 一致性水位校验（readTS <= 已复制水位）；
- 一致性模式：strong（leader 读）/ bounded（水位内读）可配置；
- 验收：五中心故障演练、跨地域读延迟与一致性边界报告。

ADR：`ADR-0123 Five-Region Topology and Global Read`。

### Goal 5 — SaaS 计费与市场控制平面

目标：多租户从配额/审计升级为计费与市场。

交付：

- `saas/`：UsageMeter（请求/存储/出向流量计量）、BillingPlan、
  MeteredBilling 原型；
- 配额联动：超限自动降级/告警（连接 Phase 28 配额）；
- 市场：ClusterTemplate 目录（规格 → 定价）；
- 验收：计费采样正确性（参数化矩阵）+ 配额降级演练。

ADR：`ADR-0124 SaaS Metering and Marketplace`。

### Goal 6 — v1.2 冻结与跨地域生产基准

目标：v1.2.0 发布候选 + 真实跨地域数据。

交付：

- `release.yml` 扩展 v1.2.0 标签；
- 跨地域基准（Linux Runner）：复制 RTT、Geo 事务延迟、分布式 SQL、
  五中心 RTO/RPO（如实记录）；
- `docs/benchmark/phase29-production-report.md`。

ADR：`ADR-0125 v1.2 Freeze and Cross-Region Benchmark`。

### Goal 7 — 分布式可观测性与告警

交付：

- 指标扩展：dist_sql_query_p99、vector_shard_skew、crdt_clock_skew、
  global_read_staleness、meter_usage；
- 告警规则（阈值 + 等级）：复制滞后、时钟偏差、分片倾斜、配额超限；
- 追踪：跨 Region 查询 span（queryId 关联）。

### Goal 8 — 规模化容灾/混沌演练

交付：

- 五中心故障矩阵：单主故障、双主故障、仲裁丢失、跨地域分区；
- `FiveRegionChaosTest` / `GlobalReadChaosTest`；
- 演练输出：RTO/RPO/读陈旧度分位报告。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0120 | Distributed SQL Execution |
| ADR-0121 | Distributed Vector Index |
| ADR-0122 | Geo CRDT Scale Validation |
| ADR-0123 | Five-Region Topology and Global Read |
| ADR-0124 | SaaS Metering and Marketplace |
| ADR-0125 | v1.2 Freeze and Cross-Region Benchmark |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 29）；

Phase 1-29 全量目标：**>=3450 tests**（当前 3216）。

| Module | Count |
| --- | ---: |
| 分布式 SQL | 50 |
| 向量分片 | 35 |
| Geo CRDT 规模/时钟 | 40 |
| 五中心/全球读 | 40 |
| SaaS 计费/市场 | 30 |
| v1.2 发布/跨地域基准 | 20 |
| 可观测性/告警 | 15 |
| 容灾/混沌演练 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase29-distributed-query-review.md
docs/sql/distributed-execution.md
docs/vector/distributed-index.md
docs/multi-region/crdt-scale-report.md
docs/dr/five-region-guide.md
docs/dr/global-read-design.md
docs/saas/metering-guide.md
docs/observability/distributed-alerting.md
docs/benchmark/phase29-production-report.md
docs/release/v1.2.0-release-notes.md
```

## 8. Engineering Rules

- v1.0/v1.1 冻结协议不变；新能力 additive；
- 单向/双向复制零回退（Phase 28 基准保持）；
- 分布式 SQL/向量以基准与召回率为验收，不隐藏失败项；
- CRDT 收敛必须性质测试 + 混沌验证（不允许分裂）；
- 跨地域数据如实记录（拓扑/RTT/分位），与单地域口径分离；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase29-distributed-query-geo`

Commits：

```text
docs: ADR-0120~0125
feat(sql): distributed execution
feat(vector): sharded index and rebalance
feat(crdt): scale validation and clock calibration
feat(dr): five region topology and global read
feat(saas): metering and marketplace
feat(ci): v1.2 release and cross-region benchmark
docs: phase29 release
```

Merge：`merge: integrate Phase29 distributed query and geo scale validation`

Checkpoint：`checkpoint-before-phase29` / `checkpoint-after-phase29`

## 10. Success Criteria

全部满足：

```text
✅ 分布式 SQL（跨 Region JOIN/聚合，基准达标）——已完成（ADR-0120）
✅ 向量分片（扩展线性 + 重平衡不中断 + 召回保持）——已完成（ADR-0121）
✅ Geo CRDT 规模验证（100 万键收敛 + 时钟校准）——10 万键进程内完成，百万键待 Runner
✅ 三地五中心（拓扑 + 故障矩阵演练 + RTO/RPO 报告）——已完成（ADR-0123）
✅ 全球一致性读（strong/bounded 模式 + 陈旧度报告）——已完成（ADR-0123）
✅ SaaS 计费/市场（计量正确性 + 配额降级）——已完成（ADR-0124）
✅ v1.2.0 发布候选（release.yml 执行）——流水线扩展完成，执行待 Runner
✅ 分布式可观测性与告警（指标/规则/追踪）——已完成（Goal 7）
✅ 全量回归 >=3450，复制路径零回退——3471/3471 PASS（新增 255）
```

## 11. 后续方向（Phase 30+，不在本阶段范围）

- 分布式事务跨 SQL（SQL 触发 2PC）
- 向量检索全球路由与冷热分片
- Geo CRDT 与 SQL 联动（CRDT 表）
- 多云/混合云部署
- 全球多活（Active-Active 全链路）
