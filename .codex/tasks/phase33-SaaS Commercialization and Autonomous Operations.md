# Phase 33 Task Prompt — SaaS Commercialization & Autonomous Operations

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
Phase 29   : 分布式 SQL + 向量分片 + Geo CRDT 规模验证 + 三地五中心
             + 全球一致性读 + SaaS 计量/市场 + 分布式告警 + v1.2 发布流水线
Phase 30   : 动态重分片 + 向量分片迁移 + SQL 写事务 + 全球读水位联动
             + 账单导出/周期结算 + 查询优化 + 容量模型 + v1.3 发布流水线
Phase 31   : 负载驱动自动重分片 + SQL 写 2PC 桥接 + 向量双写迁移
             + 全球 Active-Active + 账单周期滚动 + 多云部署/迁移
             + 企业控制台 API + v1.4 发布流水线
Phase 32   : SQL 写 2PC 生产接线 + 控制台 REST 服务 + 并发自动重分片
             + 网关冲突审计 + 全局多活自动选主 + 数据主权合规
             + v1.5 发布流水线
```

当前基线：

```text
develop   : 081e789 merge: integrate Phase32 production wiring and global validation
定位      : Enterprise-ready Distributed Database（v1.5.0）
Tests     : 4251/4251 PASS（另 6 项容器门控本地跳过）
新能力    : SQL 2PC 生产执行、REST 控制台、并发重分片、冲突审计、选主、合规
```

Phase 32 完成生产接线。Phase 33 把这些能力推向**商业化与自治**：SQL
写 2PC 真实协调器端到端、选主与 Raft term 联动、控制台 UI、SaaS
商业化闭环（计费+市场+订阅）、AI 驱动容量规划、数据网格联邦查询、
全球多活流量治理，并完成 v1.6 冻结与跨地域真实基准。

## 2. Release 前置项（Phase 25–32 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.5）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–32 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.5 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 单向/双向复制、分布式 SQL/向量、重分片、多活、生产接线零回退；
- 商业化/自治能力必须可参数化验收，不隐藏失败项。

## 3. Phase 33 Goal

目标：**SaaS Commercialization & Autonomous Operations**，完成 8 个 Goal：

1. SQL 写 2PC 真实协调器端到端
2. 选主与 Raft term 联动
3. 控制台 UI 原型
4. SaaS 商业化闭环（计费 + 市场 + 订阅）
5. AI 驱动容量规划与自动运维
6. 数据网格联邦查询
7. 全球多活流量治理
8. v1.6 冻结与跨地域真实基准

## 4. Goals

### Goal 1 — SQL 写 2PC 真实协调器端到端

目标：SqlTxn2PcExecutor 接入真实 GeoTransactionCoordinator。

交付：

- `sql/txn/SqlTxnCoordinatorAdapter`：WriteOp 分组 → 真实
  GeoTransactionCoordinator（prewrite/commit/rollback）；
- 跨 Region 写事务端到端（事务状态机对齐）；
- 验收：与原生 2PC 语义等价（提交/回滚/幂等/决策日志）。

ADR：`ADR-0144 SQL Write 2PC End-to-End Coordinator`。

### Goal 2 — 选主与 Raft term 联动

目标：LeaderSelector 接入 Raft term/epoch 防脑裂强化。

交付：

- `replication/active/RaftAwareLeaderSelector`：term 单调 + 健康探测 +
  自动选主；
- 低 term 地域不得自封 leader（防脑裂）；
- 验收：term 回退拒绝、故障切换正确。

ADR：`ADR-0145 Leader Selection with Raft Term`。

### Goal 3 — 控制台 UI 原型

目标：ConsoleRestServer + Web UI 视图。

交付：

- `console/ui/`：静态 HTML（租户/集群/账单/指标/告警视图）+
  REST 调用；
- 自服务：创建集群（TenantClusterPlanner 联动）；
- 验收：视图数据渲染 + RBAC 门控（ADMIN/READ）。

ADR：`ADR-0146 Console UI & SaaS Commercialization`。

### Goal 4 — SaaS 商业化闭环

目标：计费 + 市场 + 订阅生命周期。

交付：

- `saas/commerce/`：Subscription（active/trial/canceled）、
  MarketplaceCatalog（模板 + 定价）、BillingSubscription（周期联动）；
- 计费接 Phase 31 BillingScheduler；
- 验收：订阅状态机矩阵 + 计费联动。

ADR：`ADR-0146`。

### Goal 5 — AI 驱动容量规划与自动运维

目标：容量预测 + 自动建议。

交付：

- `capacity/ai/`：TrendPredictor（线性/指数趋势 + 置信带）、
  AutoCapacityAdvisor（预测 → 扩容建议 + 风险等级）；
- 与 CapacityPlanner（Phase 30）联动；
- 验收：预测误差矩阵 + 建议正确性。

ADR：`ADR-0147 Autonomous Capacity Planning`。

### Goal 6 — 数据网格联邦查询

目标：跨业务域联邦查询。

交付：

- `datamesh/`：FederatedPlanner（域 → 查询分片）、FederatedExecutor
  （跨域聚合）、DomainCatalog（域注册）；
- 验收：跨域 JOIN/聚合正确、域隔离（RBAC）。

ADR：`ADR-0148 Data Mesh Federated Query`。

### Goal 7 — 全球多活流量治理

目标：多地域配额/优先级流量治理。

交付：

- `gateway/`：RegionQuota（地域写入配额）、PriorityRouter（优先级
  队列）、TrafficPolicy（QPS/配额映射）；
- 与 RegionAffinityRouter（Phase 32）联动；
- 验收：配额矩阵 + 优先级降级。

ADR：`ADR-0149 Global Traffic Governance & v1.6 Freeze`。

### Goal 8 — v1.6 冻结与跨地域真实基准

目标：v1.6.0 发布候选 + 跨地域数据。

交付：

- `release.yml` 扩展 v1.6.0 标签；
- 跨地域基准（Linux Runner）：SQL 2PC 延迟、选主 RTO、联邦查询、
  流量治理吞吐（如实记录）；
- `docs/benchmark/phase33-production-report.md`。

ADR：`ADR-0149`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0144 | SQL Write 2PC End-to-End Coordinator |
| ADR-0145 | Leader Selection with Raft Term |
| ADR-0146 | Console UI & SaaS Commercialization |
| ADR-0147 | Autonomous Capacity Planning |
| ADR-0148 | Data Mesh Federated Query |
| ADR-0149 | Global Traffic Governance & v1.6 Freeze |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 33）；

Phase 1-33 全量目标：**>=4450 tests**（当前 4251）。

| Module | Count |
| --- | ---: |
| SQL 2PC 协调器端到端 | 40 |
| 选主 + Raft term | 30 |
| 控制台 UI | 25 |
| SaaS 商业化 | 40 |
| AI 容量规划 | 35 |
| 数据网格 | 35 |
| 流量治理 | 25 |
| v1.6 发布/跨地域基准 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase33-saas-autonomous-review.md
docs/sql/2pc-coordinator.md
docs/multi-region/raft-aware-leader.md
docs/console/ui-guide.md
docs/saas/commercialization.md
docs/capacity/ai-planning.md
docs/datamesh/federated-query.md
docs/gateway/traffic-governance.md
docs/benchmark/phase33-production-report.md
docs/release/v1.6.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.5 冻结协议不变；新能力 additive；
- SQL 写必须经真实 2PC（禁止旁路事务状态机）；
- 选主必须 term 单调 + 防脑裂；
- 商业化（订阅/计费）必须参数化验收；
- AI 容量建议必须输出置信度/风险等级，不隐藏失败项；
- 数据网格查询必须域隔离（RBAC）；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase33-saas-autonomous`

Commits：

```text
docs: ADR-0144~0149
feat(sql): 2pc coordinator end-to-end
feat(active): raft-aware leader selection
feat(console): ui prototype
feat(saas): commercialization
feat(capacity): ai planning
feat(datamesh): federated query
feat(gateway): traffic governance
feat(ci): v1.6 release and cross-region benchmark
docs: phase33 release
```

Merge：`merge: integrate Phase33 saas commercialization and autonomous operations`

Checkpoint：`checkpoint-before-phase33` / `checkpoint-after-phase33`

## 10. Success Criteria

全部满足：

```text
✅ SQL 写 2PC 真实协调器端到端（与原生 2PC 语义等价）
✅ 选主与 Raft term 联动（term 单调 + 防脑裂）
✅ 控制台 UI 原型（视图 + RBAC + 自服务）
✅ SaaS 商业化闭环（订阅 + 计费 + 市场）
✅ AI 容量规划（预测 + 置信带 + 建议）
✅ 数据网格联邦查询（跨域 JOIN/聚合 + 域隔离）
✅ 全球多活流量治理（配额 + 优先级）
✅ v1.6.0 发布候选（release.yml 执行）
✅ 全量回归 >=4450，复制/查询/重分片/多活/生产接线路径零回退
```

## 11. 后续方向（Phase 34+，不在本阶段范围）

- 控制台多租户 SaaS 产品化（完整 UI + 计费仪表盘）
- 全球多活自动容量与流量自治（AI 闭环）
- 数据网格跨云联邦（数据主权联动）
- 法规合规自动化（审计导出/法规映射）
- 企业级可观测性（追踪/成本归因）
