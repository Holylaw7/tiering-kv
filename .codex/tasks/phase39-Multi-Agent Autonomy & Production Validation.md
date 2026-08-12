# Phase 39 Task Prompt — Multi-Agent Autonomy & Production Validation

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
Phase 34   : 控制台 SaaS 产品化 + AI 自治闭环 + 跨云联邦 + 数据主权
             + 合规自动化 + 可观测性（追踪/成本）+ 商业化运营指标
             + v1.7 发布流水线 + JVM 级生产门禁
Phase 35   : 全球受限自治 + 跨云实时物化视图 + 合规即代码 + Workload 成本
             优化 + 多租户网络隔离 + SLA/SLO 管理 + v1.8 发布流水线
Phase 36   : 真实执行门禁收敛 v2 + 自学习围栏 + CDC 增量物化 + 合规持续
             证明 + 多云成本调度 + 网络策略即代码 + SLO 预算容量
             + v1.9 发布流水线
Phase 37   : 门禁收敛 v3 + 多目标自治 + 跨云远端物化 + 第三方证明
             + Spot 竞价 + 策略审计 + 多 SLO 谈判 + v2.0 GA 发布流水线
Phase 38   : 门禁收敛 v4 + 远端状态持久化 + 强化学习自治 + 物化视图
             生命周期 + 签名证明 + Spot 中断迁移 + 策略风险评分
             + v2.1 发布流水线
```

当前基线：

```text
develop   : a639934 merge: integrate Phase38 production convergence and autonomous intelligence
定位      : Enterprise-ready Distributed Database（v2.1.0）
Tests     : 6433/6433 PASS（另 6 项容器门控本地跳过）
新能力    : 状态持久化、强化学习自治、生命周期、签名证明、Spot 迁移、风险评分
```

Phase 38 完成单智能体自治与生产收敛。Phase 39 把系统推向**多智能体自治
与完整生产验证**：真实执行门禁 Linux Runner 收敛 v5、强化学习多智能体
联合学习、物化视图远端存储自动分层、合规证明链上链锚定、Spot 市场
实时预测、策略风险自适应加固、全局 Pareto 多目标容量优化，并完成
v2.2 冻结与发布流水线。

## 2. Release 前置项（Phase 25–38 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.1）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–38 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-066 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 38 登记，待 Runner |
| TD-067 | 强化学习为单智能体原型，未做多智能体联合学习 | Phase 38 登记 |
| TD-068 | 签名密钥无轮换机制（HMAC 抽象） | Phase 38 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v2.1 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 多智能体学习只聚合 Q/权重，禁止放宽安全核心约束；
- 自动分层必须保持 stale 语义与主权约束；
- 链上锚定必须可独立验证；
- 自适应加固必须审计可回滚；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 39 Goal

目标：**Multi-Agent Autonomy & Production Validation**，完成 8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v5
2. 强化学习多智能体自治（跨地域联合学习）
3. 物化视图远端存储自动分层（热/温/冷）
4. 合规证明链上链锚定
5. Spot 市场实时接入与中断率预测
6. 策略风险自适应加固（评分驱动自动收紧）
7. 全局 Pareto 多目标容量优化（SLO × 成本 × 风险）
8. v2.2 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v5

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063；
- 门禁收敛表 v5：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0185 Real Runner Gate Convergence v5`。

### Goal 2 — 强化学习多智能体自治

目标：单智能体 Q 学习升级为跨地域多智能体联合学习。

交付：

- `capacity/ai/MultiAgentAutonomy`：每地域本地 Q + 周期聚合（联邦
  平均/加权）→ 全局权重；
- 聚合限幅 + 安全上下界 + 审计；
- 与 ReinforcementAutonomy 联动；
- 验收：聚合矩阵 → 全局权重、本地/全局差异、越界拒绝。

ADR：`ADR-0186 Multi-Agent Reinforcement Autonomy`。

### Goal 3 — 物化视图远端存储自动分层

目标：远端物化视图按访问热度自动分层（热/温/冷）。

交付：

- `datamesh/AutoTierManager`：访问统计 → 分层决策（HOT/WARM/COLD）+
  迁移策略；
- 分层保持 stale 语义与主权约束；
- 与 MaterializedViewLifecycle 联动；
- 验收：热度矩阵 → 分层、迁移正确、主权拒绝。

ADR：`ADR-0187 Remote Materialization Auto-Tiering`。

### Goal 4 — 合规证明链上链锚定

目标：证明链哈希锚定到外部链（模拟），第三方链验证。

交付：

- `compliance/ChainAnchor`：证明链头哈希 → 锚定记录（链 ID + 区块号
   + 时间）；
- `compliance/ChainVerifier`：锚定验证 + 篡改检测；
- 与 AttestationChain / AttestationExporter 联动；
- 验收：锚定/验证矩阵 + 锚定缺失拒绝。

ADR：`ADR-0188 Blockchain-Anchored Attestation`。

### Goal 5 — Spot 市场实时接入与中断率预测

目标：spot 中断率从静态估计升级为市场数据 + 预测。

交付：

- `observability/cost/SpotMarketFeed`：模拟市场数据源（价格/中断率
   时间序列）；
- `observability/cost/SpotRatePredictor`：历史中断率 → 预测
  （移动平均/指数平滑）；
- 与 SpotAwareScheduler / SpotMigrationPlanner 联动；
- 验收：预测误差矩阵 + 市场接入正确性。

ADR：`ADR-0189 Real-Time Spot Market Prediction`。

### Goal 6 — 策略风险自适应加固

目标：风险评分驱动自动收紧策略（可回滚 + 审计）。

交付：

- `security/network/AdaptiveHardener`：风险评分阈值 → 自动撤销
  高风险白名单；
- 加固动作审计 + 回滚；
- 与 PolicyRiskScorer / PolicyAuditView 联动；
- 验收：评分阈值矩阵 + 加固/回滚 + 审计。

ADR：`ADR-0190 Adaptive Policy Hardening`。

### Goal 7 — 全局 Pareto 多目标容量优化

目标：SLO × 成本 × 风险多目标 Pareto 前沿。

交付：

- `operations/slo/ParetoCapacityOptimizer`：候选方案（节点数 × 策略）
  → 多目标评分 → Pareto 前沿；
- 与 MultiSloNegotiator / AutoCapacityAdvisor 联动；
- 验收：前沿矩阵 + 支配关系 + 权重选择。

ADR：`ADR-0191 Pareto Multi-Objective Capacity & v2.2 Freeze`。

### Goal 8 — v2.2 冻结与发布流水线

目标：v2.2.0 发布候选。

交付：

- `release.yml` 扩展 v2.2.0 标签 + Phase39BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.2.0-release-notes.md`。

ADR：`ADR-0191`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0185 | Real Runner Gate Convergence v5 |
| ADR-0186 | Multi-Agent Reinforcement Autonomy |
| ADR-0187 | Remote Materialization Auto-Tiering |
| ADR-0188 | Blockchain-Anchored Attestation |
| ADR-0189 | Real-Time Spot Market Prediction |
| ADR-0190 | Adaptive Policy Hardening |
| ADR-0191 | Pareto Multi-Objective Capacity & v2.2 Freeze |

## 6. Test Plan

新增目标：**>=400 tests**（Phase 39，surefire 口径）；

Phase 1-39 全量目标：**>=6833 tests**（当前 6433）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v5（JVM 级扩展） | 40 |
| 多智能体自治 | 60 |
| 自动分层 | 55 |
| 链上锚定 | 55 |
| Spot 市场预测 | 55 |
| 自适应加固 | 55 |
| Pareto 容量 | 60 |
| v2.2 发布/门禁 | 30 |

## 7. Documentation Deliverables

```text
docs/review/phase39-multi-agent-autonomy-review.md
docs/deployment/gate-convergence-v5.md
docs/capacity/multi-agent-autonomy.md
docs/datamesh/auto-tiering.md
docs/compliance/blockchain-anchored-attestation.md
docs/observability/spot-market-prediction.md
docs/security/adaptive-hardening.md
docs/operations/pareto-capacity.md
docs/benchmark/phase39-production-report.md
docs/release/v2.2.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.1 冻结协议不变；新能力 additive；
- 多智能体学习只聚合 Q/权重，禁止放宽安全核心约束；
- 自动分层必须 stale 语义 + 主权约束；
- 链上锚定必须可独立验证、防篡改；
- Spot 预测必须输出误差/置信，不隐藏失败项；
- 自适应加固必须审计可回滚；
- Pareto 优化必须可解释（支配关系），禁止黑盒；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase39-multi-agent-autonomy-production-validation`

Commits：

```text
docs: ADR-0185~0191
feat(gates): real runner convergence v5 jvm extensions
feat(capacity): multi agent reinforcement autonomy
feat(datamesh): remote materialization auto tiering
feat(compliance): blockchain anchored attestation
feat(observability): spot market prediction
feat(security): adaptive policy hardening
feat(operations): pareto capacity optimizer
feat(ci): v2.2 release and gate convergence v5
docs: phase39 release
```

Merge：`merge: integrate Phase39 multi-agent autonomy and production validation`

Checkpoint：`checkpoint-before-phase39` / `checkpoint-after-phase39`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v5（可执行项全绿，未执行项精确登记）
✅ 强化学习多智能体自治（本地 Q + 联邦聚合 + 限幅审计）
✅ 物化视图远端自动分层（热度 → HOT/WARM/COLD + 主权）
✅ 合规证明链上锚定（锚定/验证 + 篡改拒绝）
✅ Spot 市场实时预测（市场接入 + 中断率预测 + 误差矩阵）
✅ 策略风险自适应加固（评分驱动收紧 + 审计回滚）
✅ 全局 Pareto 容量优化（支配关系 + 权重选择）
✅ v2.2.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=6833，自治/物化/合规/成本/隔离/SLO 路径零回退
```

## 11. 后续方向（Phase 40+，不在本阶段范围）

- 多智能体分层联邦学习（拓扑感知聚合）
- 物化视图冷层归档到对象存储（S3 兼容）
- 合规证明跨链互操作（多链锚定）
- Spot 市场实时竞价（毫秒级出价）
- 自适应加固策略学习（风险 → 动作映射自进化）
- 全局 Pareto 动态重平衡（在线多目标优化）
