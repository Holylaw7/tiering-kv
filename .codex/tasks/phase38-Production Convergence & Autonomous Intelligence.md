# Phase 38 Task Prompt — Production Convergence & Autonomous Intelligence

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
Phase 37   : 门禁收敛 v3 + 多目标自治（成本 × 风险 × SLO）+ 跨云远端物化
             + 第三方证明 + Spot 竞价 + 策略审计 + 多 SLO 谈判
             + v2.0 GA 发布流水线
```

当前基线：

```text
develop   : 5581cc1 merge: integrate Phase37 multi-objective autonomy and cross-cloud materialization
定位      : Enterprise-ready Distributed Database（v2.0.0 GA）
Tests     : 6040/6040 PASS（另 6 项容器门控本地跳过）
新能力    : 多目标自治、远端物化、第三方证明、Spot 竞价、策略审计、多 SLO 谈判
```

Phase 37 完成 v2.0 GA 里程碑。Phase 38 把系统推向**生产收敛与自治智能**：
真实执行门禁 Linux Runner 收敛 v4、远端物化增量状态持久化、全球自治
策略自进化（强化学习原型）、物化视图远端存储生命周期（TTL/归档）、
合规证明公钥签名、spot 中断迁移自动化、网络策略安全评分，并完成
v2.1 冻结与发布流水线。

## 2. Release 前置项（Phase 25–37 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.0）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–37 进程内完成，跨机待执行 |
| TD-051/054/059/060 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-063 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 37 登记，待 Runner |
| TD-064 | 远端物化增量状态未持久化（重启需全量回退） | Phase 37 登记 |
| TD-065 | spot 中断率为静态估计，未接入实时市场数据 | Phase 37 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v2.0 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 强化学习原型只调策略权重，禁止放宽安全核心约束；
- 远端状态持久化必须可恢复且不丢失增量语义；
- 签名证明必须可独立验证、防篡改；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 38 Goal

目标：**Production Convergence & Autonomous Intelligence**，完成 8 个
Goal：

1. 真实执行门禁 Linux Runner 收敛 v4
2. 远端物化增量状态持久化（TD-064 关闭）
3. 全球自治策略自进化（强化学习原型）
4. 物化视图远端存储生命周期管理（TTL/归档）
5. 合规证明公钥签名
6. Spot 中断迁移自动化
7. 网络策略安全评分与风险可视化
8. v2.1 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v4

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060；
- 门禁收敛表 v4：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0178 Real Runner Gate Convergence v4`。

### Goal 2 — 远端物化增量状态持久化

目标：远端物化增量状态落盘，重启恢复（TD-064 关闭）。

交付：

- `datamesh/RemoteStateStore`：远端视图状态（key 值 + 快照 + stale）
  序列化落盘 + 恢复；
- 恢复后增量语义不丢失；损坏/缺失回退全量刷新；
- 验收：落盘/恢复矩阵 + 损坏回退矩阵。

ADR：`ADR-0179 Remote Materialization State Persistence`。

### Goal 3 — 全球自治策略自进化（强化学习原型）

目标：围栏权重从人工配置升级为基于历史结果的学习。

交付：

- `capacity/ai/ReinforcementAutonomy`：动作（放宽/收紧/保持）× 回报
  （成本 × 风险 × SLO）→ 权重更新（简化 Q 学习）；
- 学习率/折扣因子可配置，权重变化限幅；
- 与 MultiObjectiveFence 联动；
- 验收：回报矩阵 → 权重变化方向、越界拒绝。

ADR：`ADR-0180 Reinforcement-Learning Autonomy`。

### Goal 4 — 物化视图远端存储生命周期

目标：远端物化视图 TTL 过期与归档。

交付：

- `datamesh/MaterializedViewLifecycle`：TTL 过期判定 + 归档（快照导出）
  + 删除；
- 与 RemoteMaterializationManager 联动；
- 验收：TTL 矩阵 + 归档/恢复 + 过期清理。

ADR：`ADR-0181 Materialized View Lifecycle Management`。

### Goal 5 — 合规证明公钥签名

目标：证明链增加公钥签名，第三方可信校验。

交付：

- `compliance/SignedAttestation`：证明节点签名（HMAC/RSA 抽象）；
- `compliance/SignatureVerifier`：公钥验证 + 篡改检测；
- 与 AttestationChain / AttestationExporter 联动；
- 验收：签名/验证矩阵 + 密钥错误拒绝。

ADR：`ADR-0182 Signed Compliance Attestation`。

### Goal 6 — Spot 中断迁移自动化

目标：spot 实例中断时自动迁移任务。

交付：

- `observability/cost/SpotMigrationPlanner`：中断事件 → 备用云选择
  （期望成本 + 约束）→ 迁移计划；
- 与 SpotAwareScheduler 联动；
- 验收：中断迁移矩阵 + 约束拒绝 + 幂等。

ADR：`ADR-0183 Spot Interruption Migration Automation`。

### Goal 7 — 网络策略安全评分

目标：策略风险评分与可视化数据源。

交付：

- `security/network/PolicyRiskScorer`：策略风险评分（跨域白名单数量、
  deny 缺失、私有域暴露）；
- `security/network/RiskDashboard`：按租户/策略聚合的风险视图；
- 与 PolicyAuditView 联动；
- 验收：评分矩阵 + 聚合正确性。

ADR：`ADR-0184 Policy Risk Scoring & v2.1 Freeze`。

### Goal 8 — v2.1 冻结与发布流水线

目标：v2.1.0 发布候选。

交付：

- `release.yml` 扩展 v2.1.0 标签 + Phase38BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.1.0-release-notes.md`。

ADR：`ADR-0184`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0178 | Real Runner Gate Convergence v4 |
| ADR-0179 | Remote Materialization State Persistence |
| ADR-0180 | Reinforcement-Learning Autonomy |
| ADR-0181 | Materialized View Lifecycle Management |
| ADR-0182 | Signed Compliance Attestation |
| ADR-0183 | Spot Interruption Migration Automation |
| ADR-0184 | Policy Risk Scoring & v2.1 Freeze |

## 6. Test Plan

新增目标：**>=390 tests**（Phase 38，surefire 口径）；

Phase 1-38 全量目标：**>=6430 tests**（当前 6040）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v4（JVM 级扩展） | 40 |
| 远端状态持久化 | 55 |
| 强化学习自治 | 55 |
| 物化视图生命周期 | 50 |
| 签名证明 | 55 |
| Spot 中断迁移 | 50 |
| 策略风险评分 | 55 |
| v2.1 发布/门禁 | 30 |

## 7. Documentation Deliverables

```text
docs/review/phase38-production-convergence-review.md
docs/deployment/gate-convergence-v4.md
docs/datamesh/remote-state-persistence.md
docs/capacity/reinforcement-autonomy.md
docs/datamesh/materialized-view-lifecycle.md
docs/compliance/signed-attestation.md
docs/observability/spot-migration.md
docs/security/policy-risk-scoring.md
docs/benchmark/phase38-production-report.md
docs/release/v2.1.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.0 冻结协议不变；新能力 additive；
- 强化学习原型只调策略权重，禁止放宽安全核心约束；
- 远端状态持久化必须可恢复且不丢失增量语义；
- 生命周期 TTL 必须参数化验收，归档可恢复；
- 签名证明必须可独立验证、防篡改；
- spot 迁移必须约束安全 + 幂等；
- 风险评分必须可解释（规则驱动），禁止黑盒；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase38-production-convergence-autonomous-intelligence`

Commits：

```text
docs: ADR-0178~0184
feat(gates): real runner convergence v4 jvm extensions
feat(datamesh): remote state persistence
feat(capacity): reinforcement autonomy prototype
feat(datamesh): materialized view lifecycle
feat(compliance): signed attestation
feat(observability): spot interruption migration
feat(security): policy risk scoring
feat(ci): v2.1 release and gate convergence v4
docs: phase38 release
```

Merge：`merge: integrate Phase38 production convergence and autonomous intelligence`

Checkpoint：`checkpoint-before-phase38` / `checkpoint-after-phase38`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v4（可执行项全绿，未执行项精确登记）
✅ 远端物化增量状态持久化（落盘/恢复/损坏回退，TD-064 关闭）
✅ 全球自治策略自进化（回报矩阵 → 权重更新 + 限幅）
✅ 物化视图生命周期（TTL 过期 + 归档恢复）
✅ 合规证明公钥签名（签名/验证 + 篡改拒绝）
✅ Spot 中断迁移自动化（迁移计划 + 约束 + 幂等）
✅ 网络策略安全评分（规则驱动评分 + 风险视图）
✅ v2.1.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=6430，自治/物化/合规/成本/隔离/SLO 路径零回退
```

## 11. 后续方向（Phase 39+，不在本阶段范围）

- 强化学习多智能体自治（跨地域联合学习）
- 物化视图远端存储自动分层（热/温/冷）
- 合规证明链上链（区块链锚定）
- Spot 竞价市场实时接入与预测
- 策略风险自适应加固（评分驱动自动收紧）
- 全局 Pareto 多目标容量优化（SLO × 成本 × 风险）
