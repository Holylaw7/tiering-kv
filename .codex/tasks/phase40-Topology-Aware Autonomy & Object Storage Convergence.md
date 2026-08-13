# Phase 40 Task Prompt — Topology-Aware Autonomy & Object Storage Convergence

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
Phase 39   : 门禁收敛 v5 + 多智能体自治 + 远端物化自动分层 + 合规链上
             锚定 + Spot 市场预测 + 自适应加固 + Pareto 容量
             + v2.2 发布流水线
```

当前基线：

```text
develop   : 1430afd merge: integrate Phase39 multi-agent autonomy and production validation
定位      : Enterprise-ready Distributed Database（v2.2.0）
Tests     : 6878/6878 PASS（另 6 项容器门控本地跳过）
新能力    : 多智能体自治、自动分层、链上锚定、Spot 预测、自适应加固、Pareto
```

Phase 39 完成多智能体自治。Phase 40 把系统推向**拓扑感知自治与对象存储
收敛**：真实执行门禁 Linux Runner 收敛 v6、多智能体分层联邦学习、
物化视图冷层对象存储归档、合规证明跨链互操作、Spot 市场实时竞价、
自适应加固策略学习、全局 Pareto 动态重平衡，并完成 v2.3 冻结与发布
流水线。

## 2. Release 前置项（Phase 25–39 遗留，先于新功能执行）

| 编号                   | 内容                                                            | 状态                               |
| ---------------------- | --------------------------------------------------------------- | ---------------------------------- |
| TD-048                 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿）                | 交付物就绪，待执行                 |
| TD-049                 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount）                  | 交付物就绪，待执行                 |
| K8S-001                | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练）   | 脚本就绪，待执行                   |
| REL-001                | release.yml（v1.1–v2.2）真实运行记录                            | 流水线就绪，待触发                 |
| BM-001                 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner        |
| BM-002                 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准                      | Phase 27–39 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准                               | 进程内完成，跨机待 Runner          |
| TD-066/069             | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域）        | Phase 38/39 登记，待 Runner        |
| TD-070                 | 多智能体聚合为同步平均，未做异步拓扑感知聚合                    | Phase 39 登记                      |
| TD-071                 | Spot 市场为模拟数据源，未接入真实市场 API                       | Phase 39 登记                      |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v2.2 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 拓扑感知聚合只调权重/聚合策略，禁止放宽安全核心约束；
- 对象存储归档必须保持 stale 语义与主权约束；
- 跨链互操作必须可独立验证；
- 实时竞价必须约束安全 + 幂等；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 40 Goal

目标：**Topology-Aware Autonomy & Object Storage Convergence**，完成
8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v6
2. 多智能体分层联邦学习（拓扑感知聚合，TD-070 关闭方向）
3. 物化视图冷层对象存储归档（S3 兼容）
4. 合规证明跨链互操作（多链锚定）
5. Spot 市场实时竞价（毫秒级出价 + 市场 API 抽象）
6. 自适应加固策略学习（风险 → 动作映射自进化）
7. 全局 Pareto 动态重平衡（在线多目标优化）
8. v2.3 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v6

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069；
- 门禁收敛表 v6：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0192 Real Runner Gate Convergence v6`。

### Goal 2 — 多智能体分层联邦学习

目标：同步平均聚合升级为拓扑感知分层联邦学习。

交付：

- `capacity/ai/TopologyFederatedAutonomy`：地域拓扑（就近分组）→
  分层聚合（本地组 → 全局）；
- 拓扑权重可配置，聚合限幅 + 安全上下界 + 审计；
- 与 MultiAgentAutonomy 联动；
- 验收：拓扑矩阵 → 分层权重、组/全局一致性、越界拒绝。

ADR：`ADR-0193 Topology-Aware Federated Autonomy`。

### Goal 3 — 物化视图冷层对象存储归档

目标：COLD 层归档到对象存储（S3 兼容），本地释放空间。

交付：

- `datamesh/ObjectStorageArchive`：冷层视图 → 对象存储（模拟 S3）
  上传/下载/删除；
- 归档保持 stale 语义 + 主权约束；
- 与 AutoTierManager / MaterializedViewLifecycle 联动；
- 验收：归档矩阵 + 恢复 + 主权拒绝。

ADR：`ADR-0194 Object Storage Cold Tier Archive`。

### Goal 4 — 合规证明跨链互操作

目标：证明链多链锚定，跨链验证互操作。

交付：

- `compliance/CrossChainAnchor`：同头哈希多链锚定（chain-1..N）；
- `compliance/CrossChainVerifier`：任一有效链验证 + 多链一致性；
- 与 ChainAnchor / ChainVerifier 联动；
- 验收：多链矩阵 + 一致性 + 篡改拒绝。

ADR：`ADR-0195 Cross-Chain Attestation Interop`。

### Goal 5 — Spot 市场实时竞价

目标：spot 从预测升级为毫秒级实时竞价。

交付：

- `observability/cost/SpotBidEngine`：市场 tick → 出价（价格上限 +
  中断率约束）→ 中标/未中标；
- 与 SpotMarketFeed / SpotRatePredictor 联动；
- 约束安全 + 幂等；
- 验收：出价矩阵 + 约束拒绝 + 幂等。

ADR：`ADR-0196 Real-Time Spot Bidding`。

### Goal 6 — 自适应加固策略学习

目标：风险 → 动作映射从规则升级为学习。

交付：

- `security/network/LearnedHardener`：风险评分 × 历史结果 → 阈值
  自进化（简化学习）；
- 阈值变化限幅 + 审计 + 回滚；
- 与 AdaptiveHardener / PolicyRiskScorer 联动；
- 验收：学习矩阵 → 阈值变化、越界拒绝。

ADR：`ADR-0197 Learned Adaptive Hardening`。

### Goal 7 — 全局 Pareto 动态重平衡

目标：Pareto 前沿在线重平衡（随指标变化重算）。

交付：

- `operations/slo/OnlineParetoRebalancer`：指标流 → 周期重算前沿 +
  重平衡建议；
- 与 ParetoCapacityOptimizer 联动；
- 重平衡限幅 + 幂等；
- 验收：指标流矩阵 → 前沿更新、限幅、幂等。

ADR：`ADR-0198 Online Pareto Rebalancing & v2.3 Freeze`。

### Goal 8 — v2.3 冻结与发布流水线

目标：v2.3.0 发布候选。

交付：

- `release.yml` 扩展 v2.3.0 标签 + Phase40BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.3.0-release-notes.md`。

ADR：`ADR-0198`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR      | 主题                                    |
| -------- | --------------------------------------- |
| ADR-0192 | Real Runner Gate Convergence v6         |
| ADR-0193 | Topology-Aware Federated Autonomy       |
| ADR-0194 | Object Storage Cold Tier Archive        |
| ADR-0195 | Cross-Chain Attestation Interop         |
| ADR-0196 | Real-Time Spot Bidding                  |
| ADR-0197 | Learned Adaptive Hardening              |
| ADR-0198 | Online Pareto Rebalancing & v2.3 Freeze |

## 6. Test Plan

新增目标：**>=450 tests**（Phase 40，surefire 口径）；

Phase 1-40 全量目标：**>=7328 tests**（当前 6878）。

| Module                    | Count |
| ------------------------- | ----: |
| 门禁收敛 v6（JVM 级扩展） |    40 |
| 拓扑感知联邦学习          |    65 |
| 对象存储归档              |    60 |
| 跨链互操作                |    60 |
| Spot 实时竞价             |    60 |
| 学习型加固                |    60 |
| 在线 Pareto 重平衡        |    65 |
| v2.3 发布/门禁            |    40 |

## 7. Documentation Deliverables

```text
docs/review/phase40-topology-autonomy-review.md
docs/deployment/gate-convergence-v6.md
docs/capacity/topology-autonomy.md
docs/datamesh/object-storage-archive.md
docs/compliance/cross-chain-attestation.md
docs/observability/spot-bidding.md
docs/security/learned-hardening.md
docs/operations/online-pareto.md
docs/benchmark/phase40-production-report.md
docs/release/v2.3.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.2 冻结协议不变；新能力 additive；
- 拓扑感知聚合只调权重/聚合策略，禁止放宽安全核心约束；
- 对象存储归档必须 stale 语义 + 主权约束；
- 跨链互操作必须可独立验证、防篡改；
- 实时竞价必须约束安全 + 幂等；
- 学习型加固必须审计可回滚；
- 在线重平衡必须限幅 + 幂等；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase40-topology-aware-autonomy-object-storage`

Commits：

```text
docs: ADR-0192~0198
feat(gates): real runner convergence v6 jvm extensions
feat(capacity): topology aware federated autonomy
feat(datamesh): object storage cold tier archive
feat(compliance): cross chain attestation interop
feat(observability): real time spot bidding
feat(security): learned adaptive hardening
feat(operations): online pareto rebalancing
feat(ci): v2.3 release and gate convergence v6
docs: phase40 release
```

Merge：`merge: integrate Phase40 topology-aware autonomy and object storage convergence`

Checkpoint：`checkpoint-before-phase40` / `checkpoint-after-phase40`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v6（可执行项全绿，未执行项精确登记）
✅ 多智能体分层联邦学习（拓扑感知聚合 + 限幅审计）
✅ 物化视图冷层对象存储归档（上传/恢复 + 主权拒绝）
✅ 合规证明跨链互操作（多链锚定 + 一致性验证）
✅ Spot 实时竞价（出价/中标 + 约束 + 幂等）
✅ 自适应加固策略学习（风险 → 阈值自进化 + 审计回滚）
✅ 全局 Pareto 动态重平衡（指标流 → 前沿更新 + 限幅）
✅ v2.3.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=7328，自治/物化/合规/成本/隔离/SLO 路径零回退
```

## 11. 后续方向（Phase 41+，不在本阶段范围）

- 全球自治拓扑自发现（动态拓扑学习）
- 物化视图对象存储生命周期联动（S3 生命周期策略）
- 合规证明公链真实上链（真实区块链接入）
- Spot 竞价市场做市（双向报价）
- 学习型加固多策略博弈
- 在线 Pareto 与自治闭环联动（动态容量自治）
