# Phase 35 Task Prompt — Global AI Autonomy & Compliance-as-Code

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
Phase 34   : 控制台 SaaS 产品化（仪表盘/市场/订阅）+ AI 自治闭环
             （容量护栏 + 流量限幅/熔断/回滚）+ 跨云联邦 + 数据主权
             + 法规合规自动化 + 企业级可观测性（追踪 + 成本归因）
             + 商业化运营指标 + v1.7 发布流水线 + JVM 级生产门禁
```

当前基线：

```text
develop   : 447ef9b merge: integrate Phase34 saas productization and autonomous closure
定位      : Enterprise-ready Distributed Database（v1.7.0）
Tests     : 4926/4926 PASS（另 6 项容器门控本地跳过）
新能力    : SaaS 产品化、AI 自治护栏、跨云联邦、合规自动化、追踪/成本、
            商业化运营指标、v1.7 发布流水线
```

Phase 34 完成"产品化与护栏内自治"。Phase 35 把这些能力推向**全球 AI
全自治与合规即代码**：全球多活受限自治（容量/流量/重分片联动）、跨云
实时物化视图、合规即代码（持续审计流水线）、workload 感知成本优化、
多租户网络隔离、SLA/SLO 管理，并完成 v1.8 冻结与真实执行门禁收敛。

## 2. Release 前置项（Phase 25–34 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.7）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–34 进程内完成，跨机待执行 |
| TD-051 | 跨地域真实 2PC/联邦/流量基准待 Runner 执行 | Phase 33 登记 |
| TD-052 | 控制台 UI 为原型，无实时推送与完整仪表盘 | Phase 33 登记 |
| TD-053 | AI 容量预测为线性/指数模型，复杂负载需人工复核 | Phase 33 登记 |
| TD-054 | 跨地域真实门禁（2PC/联邦/流量/追踪）仍待 Runner | Phase 34 登记 |
| TD-055 | 控制台 UI 无实时推送，仪表盘为快照渲染 | Phase 34 登记 |
| TD-056 | AI 自治仍为策略护栏内执行，未做全自治审批闭环 | Phase 34 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.7 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- "全自治"= 策略围栏内自治，禁止无约束自动变更；
- 合规即代码必须可参数化验收，不隐藏失败项；
- 跨地域/容器/磁盘门禁：能执行则执行并记录，不能执行如实登记 TD。

## 3. Phase 35 Goal

目标：**Global AI Autonomy & Compliance-as-Code**，完成 8 个 Goal：

1. 全球多活受限自治（容量 + 流量 + 重分片联动闭环）
2. 跨云实时物化视图
3. 合规即代码（持续审计流水线 + 法规版本化）
4. Workload 感知成本优化引擎
5. 多租户网络隔离（VPC/私有网络/隔离域）
6. 企业级 SLA/SLO 管理
7. v1.8 冻结与发布流水线
8. 真实执行门禁收敛（可执行项全绿 + 未执行项精确登记）

## 4. Goals

### Goal 1 — 全球多活受限自治

目标：Phase 34 的容量/流量护栏接入全局编排，形成"预测 → 执行 → 验证
→ 回滚"的全球受限自治闭环。

交付：

- `capacity/ai/GlobalAutonomyOrchestrator`：跨地域容量建议 + 流量配额
  调整 + 重分片计划联动（策略围栏：日预算/地域上限/熔断）；
- `gateway/GlobalTrafficAutonomy`：多地域配额联合调整（限幅 + 回滚）；
- 验收：全局护栏矩阵（越界拒绝/回滚）、跨地域联动幂等、失败登记。

ADR：`ADR-0157 Global Restricted Autonomy`。

### Goal 2 — 跨云实时物化视图

目标：数据网格跨云物化视图（预聚合 + 刷新）。

交付：

- `datamesh/MaterializedView`：视图定义（域 + 聚合 + 刷新周期）；
- `datamesh/MaterializedViewManager`：创建/刷新/失效/查询；
- 与 CloudFederatedExecutor 联动（跨云预聚合）；
- 验收：刷新一致性矩阵 + 失效/查询正确性。

ADR：`ADR-0158 Cross-Cloud Materialized Views`。

### Goal 3 — 合规即代码

目标：法规版本化 + 持续审计流水线。

交付：

- `compliance/RegulationVersion`：法规版本（生效时间 + 控制项快照）；
- `compliance/ContinuousAuditPipeline`：周期评估 → 违规报告 →
  导出（JSON/CSV）→ 审计记录；
- 与 RegulationMapper / AuditExporter 联动；
- 验收：版本切换矩阵 + 流水线周期评估正确性。

ADR：`ADR-0159 Compliance-as-Code`。

### Goal 4 — Workload 感知成本优化引擎

目标：按 workload 特征给出降本建议（风险等级 + 收益估算）。

交付：

- `observability/cost/WorkloadCostOptimizer`：负载画像（读/写/存储/
  迁移）→ 降本建议（缩容/冷层/压缩）；
- 与 CostAttribution / AutoCapacityAdvisor 联动；
- 验收：建议正确性矩阵 + 收益/风险估算。

ADR：`ADR-0160 Workload-Aware Cost Optimization`。

### Goal 5 — 多租户网络隔离

目标：租户级网络隔离边界（VPC/私有网络/隔离域）。

交付：

- `security/network/NetworkIsolationDomain`：租户 → 网络域（VPC/子网/
  私有网络标志）；
- `security/network/IsolationPolicy`：跨域通信默认拒绝 + 白名单；
- 与 CredentialManager / TenantRegistry 联动；
- 验收：隔离矩阵 + 白名单授权 + 越权拒绝。

ADR：`ADR-0161 Multi-Tenant Network Isolation`。

### Goal 6 — 企业级 SLA/SLO 管理

目标：SLO 定义 + 达成率计算 + 告警。

交付：

- `operations/slo/SloDefinition`：指标 + 目标值 + 窗口；
- `operations/slo/SloManager`：达成率计算（滚动窗口）+ 状态；
- `operations/slo/SloAlert`：SLO 违约告警；
- 与 Phase28Metrics 联动；
- 验收：达成率矩阵 + 窗口滚动 + 告警阈值。

ADR：`ADR-0162 SLA & SLO Management`。

### Goal 7 — v1.8 冻结与发布流水线

目标：v1.8.0 发布候选 + 兼容性验证。

交付：

- `release.yml` 扩展 v1.8.0 标签 + Phase35BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v1.8.0-release-notes.md`。

ADR：`ADR-0163 v1.8 Freeze & Gate Convergence`。

### Goal 8 — 真实执行门禁收敛

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner：TD-048/049、K8S-001、REL-001、BM-001/002、TD-051/054；
- 本环境可执行部分（JVM 级混沌/基准扩展）先行验证；
- 门禁收敛表：每项给出状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记。

ADR：`ADR-0163`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0157 | Global Restricted Autonomy |
| ADR-0158 | Cross-Cloud Materialized Views |
| ADR-0159 | Compliance-as-Code |
| ADR-0160 | Workload-Aware Cost Optimization |
| ADR-0161 | Multi-Tenant Network Isolation |
| ADR-0162 | SLA & SLO Management |
| ADR-0163 | v1.8 Freeze & Gate Convergence |

## 6. Test Plan

新增目标：**>=360 tests**（Phase 35，surefire 口径）；

Phase 1-35 全量目标：**>=5286 tests**（当前 4926）。

| Module | Count |
| --- | ---: |
| 全球受限自治 | 50 |
| 跨云物化视图 | 50 |
| 合规即代码 | 50 |
| 成本优化引擎 | 45 |
| 多租户网络隔离 | 50 |
| SLA/SLO 管理 | 50 |
| v1.8 发布/门禁 | 45 |
| 真实执行门禁扩展（JVM 级） | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase35-global-autonomy-review.md
docs/capacity/global-autonomy.md
docs/datamesh/materialized-view.md
docs/compliance/compliance-as-code.md
docs/observability/cost-optimization.md
docs/security/network-isolation.md
docs/operations/slo-sla.md
docs/deployment/gate-convergence.md
docs/benchmark/phase35-production-report.md
docs/release/v1.8.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.7 冻结协议不变；新能力 additive；
- "全自治"必须策略围栏（日预算/地域上限/熔断/回滚），禁止无约束变更；
- 物化视图必须可失效/可刷新，禁止陈旧数据无标记返回；
- 合规即代码必须版本化 + 可审计；
- 成本建议必须输出收益/风险等级，不隐藏失败项；
- 网络隔离默认拒绝，白名单显式授权；
- SLO 窗口滚动必须参数化验收；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase35-global-autonomy-compliance-as-code`

Commits：

```text
docs: ADR-0157~0163
feat(capacity): global restricted autonomy orchestrator
feat(datamesh): materialized view manager
feat(compliance): regulation versions and audit pipeline
feat(observability): workload cost optimizer
feat(security): network isolation domains
feat(operations): slo sla management
feat(ci): v1.8 release and gate convergence
docs: phase35 release
```

Merge：`merge: integrate Phase35 global autonomy and compliance as code`

Checkpoint：`checkpoint-before-phase35` / `checkpoint-after-phase35`

## 10. Success Criteria

全部满足：

```text
✅ 全球多活受限自治（容量/流量/重分片联动 + 策略围栏）
✅ 跨云实时物化视图（创建/刷新/失效/查询）
✅ 合规即代码（法规版本化 + 持续审计流水线）
✅ Workload 感知成本优化（收益 + 风险等级）
✅ 多租户网络隔离（默认拒绝 + 白名单）
✅ SLA/SLO 管理（达成率 + 窗口滚动 + 告警）
✅ v1.8.0 发布候选（release.yml 执行/就绪）
✅ 真实执行门禁收敛表（可执行项全绿，未执行项精确登记）
✅ 全量回归 >=5286，复制/查询/重分片/多活/商业化/自治路径零回退
```

## 11. 后续方向（Phase 36+，不在本阶段范围）

- 全球自治策略自学习（基于历史结果自动调整围栏）
- 物化视图增量刷新（CDC 驱动）
- 法规合规持续证明（formal audit trail）
- 多云成本竞价调度
- 租户级网络策略即代码（NetworkPolicy-as-Code）
- SLO 预算驱动的容量决策
