# Phase 36 Task Prompt — Gate Convergence & Self-Learning Autonomy

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
Phase 35   : 全球受限自治（容量/流量/重分片联动 + 策略围栏）
             + 跨云实时物化视图 + 合规即代码 + Workload 成本优化
             + 多租户网络隔离 + SLA/SLO 管理 + v1.8 发布流水线
```

当前基线：

```text
develop   : ce8dbd2 merge: integrate Phase35 global autonomy and compliance as code
定位      : Enterprise-ready Distributed Database（v1.8.0）
Tests     : 5286/5286 PASS（另 6 项容器门控本地跳过）
新能力    : 全球受限自治、物化视图、合规即代码、成本优化、网络隔离、SLO
```

Phase 35 完成"策略围栏内自治"。Phase 36 把遗留门禁推向**真实执行收敛**，
并把自治/物化/合规/成本/隔离能力升级为**自学习与增量智能**：全球自治
自学习围栏、物化视图 CDC 增量刷新、合规持续证明（attestation）、多云
成本竞价调度、租户网络策略即代码、SLO 预算驱动容量决策，并完成 v1.9
冻结与真实执行门禁收敛。

## 2. Release 前置项（Phase 25–35 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.8）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–35 进程内完成，跨机待执行 |
| TD-051/054 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-052/055 | 控制台 UI 原型/快照渲染，无实时推送 | Phase 33/34 登记 |
| TD-053 | AI 容量预测为线性/指数模型，复杂负载需人工复核 | Phase 33 登记 |
| TD-056 | AI 自治仍为策略护栏内执行，未做自学习围栏 | Phase 34 登记 |
| TD-057 | 全球自治未做自学习围栏 | Phase 35 登记 |
| TD-058 | 物化视图为周期刷新，无 CDC 增量刷新 | Phase 35 登记 |
| TD-059 | 真实跨地域门禁仍待 Runner | Phase 35 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.8 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 自学习围栏只调整策略参数，禁止放宽安全核心约束；
- 增量物化必须保持 stale 语义，禁止无标记陈旧返回；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记阻塞原因。

## 3. Phase 36 Goal

目标：**Gate Convergence & Self-Learning Autonomy**，完成 8 个 Goal：

1. 真实执行门禁收敛（Linux Runner：CI 容器 / 磁盘混沌 / kind / release /
   跨机跨地域基准）
2. 全球自治自学习围栏（基于历史结果自动调整围栏参数）
3. 物化视图 CDC 增量刷新
4. 合规持续证明（formal attestation + 审计链）
5. 多云成本竞价调度
6. 租户网络策略即代码（NetworkPolicy-as-Code）
7. SLO 预算驱动容量决策
8. v1.9 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁收敛

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048（CI 容器 E2E + 故障注入 3 连绿）、TD-049
  （真实块设备磁盘混沌）、K8S-001（kind 集群验证）、REL-001（release
  流水线运行记录）、BM-001/002（跨机/跨地域基准）；
- 本环境可执行部分（JVM 级混沌/基准扩展）先行验证；
- 门禁收敛表 v2：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0164 Real Runner Gate Convergence`。

### Goal 2 — 全球自治自学习围栏

目标：围栏参数从静态配置升级为基于历史结果自适应。

交付：

- `capacity/ai/SelfLearningFence`：记录执行结果（成功/回滚/告警）→
  调整日预算 / 单步上限 / 地域上限（限幅内自适应）；
- 学习规则：连续成功 → 温和放宽；连续失败/回滚 → 收紧并熔断；
- 护栏：参数变化限幅、安全上下界、审计日志；
- 验收：学习矩阵（成功/失败序列 → 参数变化）、上下界约束。

ADR：`ADR-0165 Self-Learning Autonomy Fences`。

### Goal 3 — 物化视图 CDC 增量刷新

目标：物化视图从周期全量刷新升级为 CDC 增量刷新。

交付：

- `datamesh/CdcMaterializedViewRefresher`：变更流（key + 版本）→
  增量聚合更新；
- 与 Phase 26 CDC 能力联动（增量事件源）；
- 增量失败回退全量刷新 + stale 标记；
- 验收：增量正确性矩阵（插入/更新/删除）+ 回退矩阵。

ADR：`ADR-0166 CDC Incremental Materialized Views`。

### Goal 4 — 合规持续证明

目标：合规从"报告"升级为"可验证证明"。

交付：

- `compliance/ComplianceAttestation`：审计运行 → 哈希链证明
  （regulation + version + violations + prevHash）；
- `compliance/AttestationChain`：连续证明链 + 验证 API；
- 与 ContinuousAuditPipeline 联动；
- 验收：证明链校验矩阵 + 篡改检测。

ADR：`ADR-0167 Compliance Continuous Proof`。

### Goal 5 — 多云成本竞价调度

目标：按成本与约束选择最优执行云。

交付：

- `observability/cost/CloudCostScheduler`：任务 → 候选云（价格 + 配额
  + 数据主权）→ 最低成本选择；
- 约束：数据主权（DataResidencyPolicy）、SLO、配额；
- 验收：竞价选择矩阵 + 约束拒绝矩阵。

ADR：`ADR-0168 Multi-Cloud Cost-Aware Scheduling`。

### Goal 6 — 租户网络策略即代码

目标：网络隔离策略声明式管理（YAML 风格 DSL + 校验）。

交付：

- `security/network/NetworkPolicyDsl`：声明式策略（allow/deny + 租户
  对）解析与校验；
- `security/network/PolicyCompiler`：DSL → IsolationPolicy 白名单；
- 验收：DSL 解析矩阵 + 非法策略拒绝 + 编译幂等。

ADR：`ADR-0169 NetworkPolicy-as-Code`。

### Goal 7 — SLO 预算驱动容量决策

目标：SLO 达成率预算驱动容量扩缩建议。

交付：

- `operations/slo/SloBudgetPlanner`：SLO 窗口达成率 → 容量预算
  （余量/缺口）→ 扩容建议；
- 与 SloManager / AutoCapacityAdvisor 联动；
- 验收：预算矩阵（达成率 → 建议）+ 阈值边界。

ADR：`ADR-0170 SLO-Budget-Driven Capacity & v1.9 Freeze`。

### Goal 8 — v1.9 冻结与发布流水线

目标：v1.9.0 发布候选 + 兼容性验证。

交付：

- `release.yml` 扩展 v1.9.0 标签 + Phase36BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v1.9.0-release-notes.md`。

ADR：`ADR-0170`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0164 | Real Runner Gate Convergence |
| ADR-0165 | Self-Learning Autonomy Fences |
| ADR-0166 | CDC Incremental Materialized Views |
| ADR-0167 | Compliance Continuous Proof |
| ADR-0168 | Multi-Cloud Cost-Aware Scheduling |
| ADR-0169 | NetworkPolicy-as-Code |
| ADR-0170 | SLO-Budget-Driven Capacity & v1.9 Freeze |

## 6. Test Plan

新增目标：**>=370 tests**（Phase 36，surefire 口径）；

Phase 1-36 全量目标：**>=5656 tests**（当前 5286）。

| Module | Count |
| --- | ---: |
| 门禁收敛（JVM 级扩展） | 40 |
| 自学习围栏 | 50 |
| CDC 增量物化 | 55 |
| 合规持续证明 | 50 |
| 多云成本调度 | 50 |
| 网络策略即代码 | 50 |
| SLO 预算容量 | 45 |
| v1.9 发布/门禁 | 30 |

## 7. Documentation Deliverables

```text
docs/review/phase36-gate-convergence-review.md
docs/deployment/gate-convergence-v2.md
docs/capacity/self-learning-autonomy.md
docs/datamesh/cdc-materialized-view.md
docs/compliance/continuous-proof.md
docs/observability/cost-aware-scheduling.md
docs/security/network-policy-as-code.md
docs/operations/slo-budget-capacity.md
docs/benchmark/phase36-production-report.md
docs/release/v1.9.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.8 冻结协议不变；新能力 additive；
- 自学习围栏只调整策略参数，禁止放宽安全核心约束；
- CDC 增量刷新失败必须回退全量并标记 stale；
- 合规证明链必须可验证、防篡改；
- 多云调度必须满足数据主权与 SLO 约束；
- 网络策略 DSL 非法输入必须拒绝；
- SLO 预算阈值必须参数化验收；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase36-gate-convergence-self-learning-autonomy`

Commits：

```text
docs: ADR-0164~0170
feat(gates): real runner convergence jvm extensions
feat(capacity): self-learning autonomy fences
feat(datamesh): cdc incremental materialized views
feat(compliance): attestation chain
feat(observability): multi-cloud cost aware scheduling
feat(security): network policy as code
feat(operations): slo budget driven capacity
feat(ci): v1.9 release and gate convergence v2
docs: phase36 release
```

Merge：`merge: integrate Phase36 gate convergence and self-learning autonomy`

Checkpoint：`checkpoint-before-phase36` / `checkpoint-after-phase36`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v2（可执行项全绿，未执行项精确登记）
✅ 全球自治自学习围栏（成功放宽/失败收紧 + 上下界 + 审计）
✅ 物化视图 CDC 增量刷新（插入/更新/删除 + 回退 stale）
✅ 合规持续证明（哈希链 + 验证 + 篡改检测）
✅ 多云成本竞价调度（最低成本 + 主权/SLO 约束）
✅ 租户网络策略即代码（DSL 解析 + 编译 + 非法拒绝）
✅ SLO 预算驱动容量决策（达成率 → 建议）
✅ v1.9.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=5656，自治/物化/合规/成本/隔离/SLO 路径零回退
```

## 11. 后续方向（Phase 37+，不在本阶段范围）

- 自学习围栏多目标优化（成本 × 风险 × SLO）
- 物化视图跨云物化存储（远端物化 + 增量同步）
- 合规证明跨机构验证（第三方 attestation）
- 多云 spot 实例竞价（中断感知调度）
- 网络策略跨租户审计与可视化
- SLO 预算自动谈判（多 SLO 联合优化）
