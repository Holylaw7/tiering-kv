# Phase 37 Task Prompt — Multi-Objective Autonomy & Cross-Cloud Materialization

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
             + 控制台 UI 原型 + SaaS 商业化闭环 + AI 容量规划
             + 数据网格联邦查询 + 全球流量治理 + v1.6 发布流水线
Phase 34   : 控制台 SaaS 产品化 + AI 自治闭环（容量护栏 + 流量熔断/回滚）
             + 跨云联邦 + 数据主权 + 合规自动化 + 可观测性（追踪/成本）
             + 商业化运营指标 + v1.7 发布流水线 + JVM 级生产门禁
Phase 35   : 全球受限自治 + 跨云实时物化视图 + 合规即代码 + Workload 成本
             优化 + 多租户网络隔离 + SLA/SLO 管理 + v1.8 发布流水线
Phase 36   : 真实执行门禁收敛 v2 + 全球自治自学习围栏 + CDC 增量物化
             + 合规持续证明（哈希链）+ 多云成本竞价调度 + 网络策略即代码
             + SLO 预算驱动容量 + v1.9 发布流水线
```

当前基线：

```text
develop   : a74a6fd merge: integrate Phase36 gate convergence and self-learning autonomy
定位      : Enterprise-ready Distributed Database（v1.9.0）
Tests     : 5660/5660 PASS（另 6 项容器门控本地跳过）
新能力    : 门禁收敛 v2、自学习围栏、CDC 增量物化、合规证明链、多云调度、
            策略即代码、SLO 预算容量
```

Phase 36 完成"自学习与增量智能"。Phase 37 把这些能力推向**多目标优化与
跨云执行**：真实执行门禁 Linux Runner 收敛、自学习围栏多目标优化
（成本 × 风险 × SLO）、跨云远端物化存储、合规证明跨机构验证、多云
spot 中断感知调度、网络策略跨租户审计、多 SLO 预算自动谈判，并完成
**v2.0 GA 里程碑冻结**与发布流水线。

## 2. Release 前置项（Phase 25–36 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.9）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–36 进程内完成，跨机待执行 |
| TD-051/054/059 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-060 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 36 登记，待 Runner |
| TD-061 | 自学习围栏为单指标反馈，未做多目标优化 | Phase 36 登记 |
| TD-062 | CDC 增量物化未持久化增量状态（重启需全量回退） | Phase 36 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.9 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 多目标优化只调整策略权重/参数，禁止放宽安全核心约束；
- 远端物化必须保持 stale 语义与主权约束；
- 第三方证明必须可独立验证；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 37 Goal

目标：**Multi-Objective Autonomy & Cross-Cloud Materialization**，完成
8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v3
2. 自学习围栏多目标优化（成本 × 风险 × SLO）
3. 跨云远端物化存储（远端物化 + 增量同步）
4. 合规证明跨机构验证（第三方 attestation）
5. 多云 spot 实例竞价（中断感知调度）
6. 网络策略跨租户审计与可视化
7. 多 SLO 预算自动谈判（联合优化）
8. v2.0 GA 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v3

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048（CI 容器 E2E + 故障注入 3 连绿）、TD-049
  （真实块设备磁盘混沌）、K8S-001（kind）、REL-001（release 运行记录）、
  BM-001/002（跨机/跨地域基准）；
- 门禁收敛表 v3：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0171 Real Runner Gate Convergence v3`。

### Goal 2 — 自学习围栏多目标优化

目标：围栏学习从单指标升级为成本 × 风险 × SLO 多目标加权。

交付：

- `capacity/ai/MultiObjectiveFence`：多指标反馈（成本节约/失败率/SLO
  达成）→ 加权评分 → 围栏参数调整；
- 权重可配置（成本 vs 风险 vs SLO），参数变化限幅 + 上下界 + 审计；
- 验收：权重矩阵 → 参数变化方向、约束越界拒绝。

ADR：`ADR-0172 Multi-Objective Self-Learning Fences`。

### Goal 3 — 跨云远端物化存储

目标：物化视图在远端云物化 + 增量同步，避免每次查询跨云聚合。

交付：

- `datamesh/RemoteMaterializationManager`：远端物化定义（云 + 域 +
  聚合）→ 远端落盘 + 增量同步；
- 增量同步复用 CDC 增量（ADR-0166）；
- 主权约束：跨驻留物化默认拒绝；
- 验收：远端物化正确性 + 同步一致性 + 主权拒绝矩阵。

ADR：`ADR-0173 Cross-Cloud Remote Materialization`。

### Goal 4 — 合规证明跨机构验证

目标：证明链可被第三方独立验证。

交付：

- `compliance/AttestationVerifier`：独立验证 API（不依赖原链状态）；
- `compliance/AttestationExporter`：证明导出（JSON）供第三方校验；
- 与 AttestationChain 联动；
- 验收：独立验证矩阵 + 篡改/断裂检测。

ADR：`ADR-0174 Third-Party Compliance Attestation`。

### Goal 5 — 多云 spot 实例竞价

目标：成本调度扩展 spot 竞价（中断感知）。

交付：

- `observability/cost/SpotAwareScheduler`：候选云 + spot 价格/中断率
  → 期望成本（价格 × 中断惩罚）→ 选择；
- 中断感知：高中断率候选提高惩罚系数；
- 约束：数据主权 / 配额 / SLO 不变；
- 验收：竞价选择矩阵 + 中断率影响 + 约束拒绝。

ADR：`ADR-0175 Spot-Aware Cost Scheduling`。

### Goal 6 — 网络策略跨租户审计

目标：跨租户策略变更审计与可视化数据源。

交付：

- `security/network/NetworkPolicyAudit`：策略变更事件（DSL 来源 +
  时间 + 动作）记录；
- `security/network/PolicyAuditView`：按租户/时间聚合的可视化数据源；
- 与 PolicyCompiler 联动（编译时自动审计）；
- 验收：审计矩阵 + 视图聚合正确性。

ADR：`ADR-0176 Cross-Tenant Network Policy Audit`。

### Goal 7 — 多 SLO 预算自动谈判

目标：多个 SLO 联合优化容量预算。

交付：

- `operations/slo/MultiSloNegotiator`：多 SLO（达成率 × 权重）→
  联合预算缺口 → 容量建议；
- 权重可配置，最差 SLO 优先；
- 与 SloBudgetPlanner 联动；
- 验收：联合矩阵 + 最差优先 + 权重影响。

ADR：`ADR-0177 Multi-SLO Budget Negotiation & v2.0 Freeze`。

### Goal 8 — v2.0 GA 冻结与发布流水线

目标：v2.0.0 GA 里程碑发布候选。

交付：

- `release.yml` 扩展 v2.0.0 标签 + Phase37BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.0.0-release-notes.md`（GA 里程碑）。

ADR：`ADR-0177`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0171 | Real Runner Gate Convergence v3 |
| ADR-0172 | Multi-Objective Self-Learning Fences |
| ADR-0173 | Cross-Cloud Remote Materialization |
| ADR-0174 | Third-Party Compliance Attestation |
| ADR-0175 | Spot-Aware Cost Scheduling |
| ADR-0176 | Cross-Tenant Network Policy Audit |
| ADR-0177 | Multi-SLO Budget Negotiation & v2.0 Freeze |

## 6. Test Plan

新增目标：**>=380 tests**（Phase 37，surefire 口径）；

Phase 1-37 全量目标：**>=6040 tests**（当前 5660）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v3（JVM 级扩展） | 40 |
| 多目标围栏 | 55 |
| 远端物化 | 55 |
| 第三方证明 | 50 |
| spot 调度 | 50 |
| 策略审计 | 50 |
| 多 SLO 谈判 | 50 |
| v2.0 发布/门禁 | 30 |

## 7. Documentation Deliverables

```text
docs/review/phase37-multi-objective-autonomy-review.md
docs/deployment/gate-convergence-v3.md
docs/capacity/multi-objective-autonomy.md
docs/datamesh/remote-materialization.md
docs/compliance/third-party-attestation.md
docs/observability/spot-aware-scheduling.md
docs/security/network-policy-audit.md
docs/operations/multi-slo-negotiation.md
docs/benchmark/phase37-production-report.md
docs/release/v2.0.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.9 冻结协议不变；新能力 additive；
- 多目标优化只调整策略权重/参数，禁止放宽安全核心约束；
- 远端物化必须主权校验 + stale 语义；
- 第三方证明必须可独立验证、防篡改；
- spot 调度必须中断感知，禁止忽略中断成本；
- 策略审计必须记录来源与时间，禁止无审计变更；
- 多 SLO 谈判必须最差优先 + 权重参数化；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase37-multi-objective-autonomy-cross-cloud-materialization`

Commits：

```text
docs: ADR-0171~0177
feat(gates): real runner convergence v3 jvm extensions
feat(capacity): multi objective self-learning fences
feat(datamesh): remote materialization manager
feat(compliance): third party attestation verifier
feat(observability): spot aware cost scheduling
feat(security): network policy audit
feat(operations): multi slo negotiation
feat(ci): v2.0 release and gate convergence v3
docs: phase37 release
```

Merge：`merge: integrate Phase37 multi-objective autonomy and cross-cloud materialization`

Checkpoint：`checkpoint-before-phase37` / `checkpoint-after-phase37`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v3（可执行项全绿，未执行项精确登记）
✅ 自学习围栏多目标优化（成本 × 风险 × SLO 加权 + 限幅 + 审计）
✅ 跨云远端物化（远端落盘 + 增量同步 + 主权拒绝）
✅ 合规证明跨机构验证（独立验证 + 导出）
✅ 多云 spot 竞价（中断感知期望成本）
✅ 网络策略跨租户审计（变更记录 + 视图聚合）
✅ 多 SLO 预算自动谈判（最差优先 + 权重）
✅ v2.0.0 GA 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=6040，自治/物化/合规/成本/隔离/SLO 路径零回退
```

## 11. 后续方向（Phase 38+，不在本阶段范围）

- 全球自治策略自进化（强化学习）
- 物化视图远端存储生命周期管理（TTL/归档）
- 合规证明链上链（区块链/公钥签名）
- spot 中断迁移自动化（实例级故障转移）
- 网络策略安全评分与风险可视化
- 多 SLO 与成本联合优化（全局 Pareto）
