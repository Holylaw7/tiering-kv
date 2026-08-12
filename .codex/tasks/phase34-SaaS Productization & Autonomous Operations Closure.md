# Phase 34 Task Prompt — SaaS Productization & Autonomous Operations Closure

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
Phase 33   : SQL 写 2PC 真实协调器端到端 + 选主与 Raft term 联动
             + 控制台 UI 原型 + SaaS 商业化闭环（订阅/市场/计费）
             + AI 容量规划 + 数据网格联邦查询 + 全球流量治理
             + v1.6 发布流水线
```

当前基线：

```text
develop   : e45e4f0 merge: integrate Phase33 saas commercialization and autonomous operations
定位      : Enterprise-ready Distributed Database（v1.6.0）
Tests     : 4570/4570 PASS（另 6 项容器门控本地跳过）
新能力    : SQL 2PC 真实协调器、Raft term 选主、控制台 UI、SaaS 商业化、
            AI 容量规划、数据网格联邦查询、全球流量治理
```

Phase 33 完成"能力具备"。Phase 34 把这些能力推向**产品化与自治闭环**：
控制台多租户 SaaS 产品化、AI 容量/流量自治闭环、数据网格跨云联邦、
法规合规自动化、企业级可观测性（追踪 + 成本归因）、商业化运营指标，
并完成 v1.7 冻结与真实执行门禁（跨地域基准 / CI 容器 E2E / kind / 磁盘混沌）。

## 2. Release 前置项（Phase 25–33 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.6）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–33 进程内完成，跨机待执行 |
| TD-051 | 跨地域真实 2PC/联邦/流量基准待 Runner 执行 | Phase 33 登记 |
| TD-052 | 控制台 UI 为原型，无实时推送与完整仪表盘 | Phase 33 登记 |
| TD-053 | AI 容量预测为线性/指数模型，复杂负载需人工复核 | Phase 33 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.6 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 商业化/自治/可观测性能力必须可参数化验收，不隐藏失败项；
- 跨地域/容器/磁盘门禁：能执行则执行并记录，不能执行如实登记 TD。

## 3. Phase 34 Goal

目标：**SaaS Productization & Autonomous Operations Closure**，完成 8 个
Goal：

1. 控制台多租户 SaaS 产品化（完整 UI + 计费仪表盘）
2. AI 自治闭环（容量 + 流量策略：建议 → 执行 → 验证，带护栏）
3. 数据网格跨云联邦（数据主权联动）
4. 法规合规自动化（审计导出 + 法规映射）
5. 企业级可观测性（追踪 + 成本归因）
6. 商业化运营指标（MRR / 试用转化 / 流失 + 告警）
7. v1.7 冻结与发布流水线
8. 真实执行门禁（跨地域基准 / CI 容器 / kind / 磁盘混沌，如实登记）

## 4. Goals

### Goal 1 — 控制台多租户 SaaS 产品化

目标：ConsoleUiService 从原型升级为可演示产品化控制台。

交付：

- `console/ui/`：订阅管理视图、计费仪表盘（周期收入/用量趋势）、
  市场自服务下单（MarketplaceCatalog 联动）；
- `console/api/SaasConsoleApi`：订阅/计费/市场 REST 端点（RBAC）；
- 验收：视图数据渲染 + 下单 → 订阅 → 计费闭环 + RBAC 矩阵。

ADR：`ADR-0150 Console SaaS Productization`。

### Goal 2 — AI 自治闭环（容量 + 流量）

目标：容量建议与流量策略从"建议"进入"护栏内执行"。

交付：

- `capacity/ai/AutonomousCapacityController`：预测 → 建议 → 批准
  （策略）→ 执行 CapacityPlanner 节点变更 → 验证；
- `gateway/AutonomousTrafficController`：基于预测动态调整
  RegionQuota / TrafficPolicy（限幅 + 熔断 + 回滚）；
- 护栏：单步调整上限、日调整上限、高水位拒绝执行；
- 验收：护栏矩阵（越界拒绝/回滚）、执行幂等、失败登记。

ADR：`ADR-0151 Autonomous Capacity & Traffic Closure`。

### Goal 3 — 数据网格跨云联邦

目标：联邦查询跨云/跨地域执行并联动数据主权。

交付：

- `datamesh/CloudFederatedExecutor`：域 → 云/地域分片执行；
- 数据主权校验：跨驻留边界联邦查询默认拒绝（ComplianceValidator
  联动）；
- 验收：跨云聚合正确 + 主权违规拒绝矩阵。

ADR：`ADR-0152 Cross-Cloud Data Mesh with Sovereignty`。

### Goal 4 — 法规合规自动化

目标：审计导出 + 法规映射 + 违规报告。

交付：

- `compliance/RegulationMapper`：法规 → 控制项映射；
- `compliance/AuditExporter`：JSON/CSV 审计导出（TenantAuditLog +
  ComplianceValidator）；
- `compliance/ComplianceReport`：违规项 + 严重级；
- 验收：导出格式矩阵 + 映射覆盖率 + 违规报告正确性。

ADR：`ADR-0153 Compliance Automation`。

### Goal 5 — 企业级可观测性（追踪 + 成本归因）

目标：分布式追踪与成本归因。

交付：

- `observability/tracing/`：Span/Trace 上下文（跨 RPC 传播）+
  TraceSampler + TraceExporter（JSON）；
- `observability/cost/`：CostAttribution（租户/域/云 → 资源成本）；
- 与 Phase28Metrics / Prometheus 导出联动；
- 验收：跨 RPC 追踪完整链路、成本归因矩阵。

ADR：`ADR-0154 Enterprise Observability: Tracing & Cost Attribution`。

### Goal 6 — 商业化运营指标

目标：MRR / 试用转化 / 流失 + 告警。

交付：

- `saas/operations/`：MrrCalculator（周期收入）、TrialConversionTracker、
  ChurnDetector（取消率阈值）、CommercialAlert（告警规则）；
- 与 BillingSubscription / Subscription 状态机联动；
- 验收：MRR 计算矩阵 + 转化/流失阈值矩阵。

ADR：`ADR-0155 Commercial Operations Metrics`。

### Goal 7 — v1.7 冻结与发布流水线

目标：v1.7.0 发布候选 + 兼容性验证。

交付：

- `release.yml` 扩展 v1.7.0 标签 + Phase34BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v1.7.0-release-notes.md`。

ADR：`ADR-0156 v1.7 Freeze & Real Execution Gates`。

### Goal 8 — 真实执行门禁

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner：CI 容器 E2E（TD-048）、真实磁盘混沌（TD-049）、
  kind 验证（K8S-001）、release 流水线（REL-001）、跨机/跨地域基准
  （BM-001/002、TD-051）；
- 本环境可执行部分（JVM 级混沌/基准扩展）先行验证；
- 验收：可执行项全绿 + 未执行项如实登记并给出精确阻塞原因。

ADR：`ADR-0156`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0150 | Console SaaS Productization |
| ADR-0151 | Autonomous Capacity & Traffic Closure |
| ADR-0152 | Cross-Cloud Data Mesh with Sovereignty |
| ADR-0153 | Compliance Automation |
| ADR-0154 | Enterprise Observability: Tracing & Cost Attribution |
| ADR-0155 | Commercial Operations Metrics |
| ADR-0156 | v1.7 Freeze & Real Execution Gates |

## 6. Test Plan

新增目标：**>=320 tests**（Phase 34，surefire 口径）；

Phase 1-34 全量目标：**>=4890 tests**（当前 4570）。

| Module | Count |
| --- | ---: |
| 控制台 SaaS 产品化 | 45 |
| AI 自治闭环 | 40 |
| 跨云联邦 + 数据主权 | 40 |
| 合规自动化 | 45 |
| 可观测性（追踪 + 成本） | 50 |
| 商业化运营指标 | 45 |
| v1.7 发布/门禁 | 35 |
| 真实执行门禁扩展（JVM 级） | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase34-saas-productization-review.md
docs/console/saas-dashboard.md
docs/capacity/autonomous-closure.md
docs/datamesh/cross-cloud-federation.md
docs/compliance/automation.md
docs/observability/tracing-cost.md
docs/saas/operations-metrics.md
docs/deployment/real-runner-gates.md
docs/benchmark/phase34-production-report.md
docs/release/v1.7.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.6 冻结协议不变；新能力 additive；
- 自治执行必须护栏（限幅/熔断/回滚），禁止无约束自动扩容；
- 跨云联邦必须数据主权校验；
- 合规导出必须可参数化验收；
- 追踪不得引入额外 RPC 状态机修改（仅观测）；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase34-saas-productization-autonomous-closure`

Commits：

```text
docs: ADR-0150~0156
feat(console): saas productization ui and api
feat(capacity): autonomous closure controllers
feat(datamesh): cross-cloud federated executor
feat(compliance): regulation mapper and audit exporter
feat(observability): tracing and cost attribution
feat(saas): commercial operations metrics
feat(ci): v1.7 release and real execution gates
docs: phase34 release
```

Merge：`merge: integrate Phase34 saas productization and autonomous closure`

Checkpoint：`checkpoint-before-phase34` / `checkpoint-after-phase34`

## 10. Success Criteria

全部满足：

```text
✅ 控制台 SaaS 产品化（完整 UI + 计费仪表盘 + 自服务下单）
✅ AI 自治闭环（建议 → 护栏执行 → 验证，越界拒绝/回滚）
✅ 数据网格跨云联邦（跨云聚合 + 数据主权拒绝矩阵）
✅ 法规合规自动化（审计导出 + 法规映射 + 违规报告）
✅ 企业级可观测性（跨 RPC 追踪 + 成本归因）
✅ 商业化运营指标（MRR + 试用转化 + 流失告警）
✅ v1.7.0 发布候选（release.yml 执行/就绪）
✅ 真实执行门禁：可执行项全绿，未执行项如实登记
✅ 全量回归 >=4890，复制/查询/重分片/多活/商业化路径零回退
```

## 11. 后续方向（Phase 35+，不在本阶段范围）

- 全球多活 AI 全自治（无人工审批的受限自治）
- 数据网格跨云实时物化视图
- 法规合规持续审计（合规即代码）
- 成本优化引擎（workload 感知降本）
- 多租户安全边界强化（VPC/私有网络）
- 企业级容量 SLA 与 SLO 管理
