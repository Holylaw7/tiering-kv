# Phase 47 Task Prompt — Real Runner Closure Archive & Global Consistency GA

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
Phase 40   : 门禁收敛 v6 + 拓扑感知联邦自治 + 对象存储归档 + 跨链互操作
             + Spot 实时竞价 + 学习型加固 + 在线 Pareto
             + v2.3 发布流水线
Phase 41   : 门禁收敛 v7 + 真实 S3 接入 + Spot 真实数据源 + 密钥轮换
             + 对象生命周期联动 + 生产级 LSM 演进 + PD 等价调度
             + v2.4 发布流水线
Phase 42   : 门禁收敛 v8 + Leveled 执行 + 悲观事务 + Async Commit +
             resolved-ts + Coprocessor 下推 + 自治 PD 调度 + 拓扑自发现
             + v2.5 发布流水线
Phase 43   : 门禁收敛 v9 + 跨区一阶段 + 多算子联合下推 + TSO 集群化
             + 自治 PD 与全球自治联动 + 生产基线 + 真实凭据探测
             + v2.6 发布流水线
Phase 44   : 门禁收敛 v10 + 全局一阶段规模化 + 全算子联合下推
             + TSO 跨地域容灾 + 自治 PD 全自动 + TiKV 对比基线
             + 真实凭据 v2 + v2.7 发布流水线
Phase 45   : 门禁收敛 v11 + 跨云全局一阶段 + 多表 JOIN/窗口函数下推
             + TSO 全球统一时钟 + 自治 PD 无人值守 + TiKV 跨机对比基线
             + 真实凭据 v3 + v2.8 发布流水线
Phase 46   : 门禁收敛 v12 + 跨云一阶段规模化 + 窗口函数全族/动态下推
             + TSO 跨云授时仲裁 + 自治合规自动化 + TiKV 跨机回归
             + 真实凭据 v4 + v2.9 发布流水线
```

当前基线：

```text
develop   : b193ea5 merge: integrate Phase46 real runner gate closure and global consistency finalization
定位      : Enterprise-ready Distributed Database（v2.9.0 发布候选）
Tests     : 10503/10503 PASS（另 6 项容器门控本地跳过）
新能力    : 门禁收敛 v12、跨云一阶段规模化、窗口函数全族/动态下推、
            TSO 跨云授时仲裁/防回拨、自治合规自动化、TiKV 跨机回归、
            真实凭据权限握手 v4
```

Phase 46 完成真实 Runner 门禁闭环与全球一致性最终化。Phase 47 把系统
推向**真实 Runner 闭环归档与全球一致性 GA**：真实执行门禁收敛 v13
（执行/归档/登记）、跨云一阶段全球统一（任意拓扑自动仲裁）、动态下推
强化学习、TSO 全球统一时钟（量子/卫星授时原型）、自治无人值守监管级
审计、TiKV 真实跨机基准定期回归 + 趋势告警、真实凭据网络验证 v5，
并完成 **v3.0** 冻结与发布流水线。

## 2. Release 前置项（Phase 25–46 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行（v12 登记） |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行（v12 登记） |
| K8S-001 | kind 集群内验证 | 脚本就绪，待执行（v12 登记） |
| REL-001 | release.yml（v1.1–v2.9）真实运行记录 | 流水线就绪，待触发（v12 登记） |
| BM-001 | 跨机 Production Benchmark | 本地口径完成，跨机待 Runner（v12 登记） |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间 | 进程内完成，跨机待 Runner（v12 登记） |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner（v12 登记） |
| TD-066/069/072/075/078 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机） | 登记完成，待 Runner（v12 登记） |
| TD-076 | S3/Spot 真实凭据/网络验证（权限握手 JVM 已绿） | 真实网络待 Runner（v12 登记） |
| TD-079 | 跨云一阶段（JVM 已绿）→ Phase 47 全球统一 | JVM 完成，统一仲裁待做 |
| TD-080 | 窗口全族/动态下推（JVM 已绿）→ Phase 47 强化学习 | JVM 完成，RL 待做 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v2.9 冻结协议不变，v3.0 扩展必须走 ADR-0103 兼容评审；
- 跨云一阶段必须回退 2PC 兜底；
- RL 动态下推必须与上层 SQL 结果一致（决策层强化，语义层不变）；
- TSO 全球统一时钟必须单调且防时钟回拨；
- 监管级审计必须可验证、可导出、可轮换；
- Benchmark 基线必须如实记录（对比口径注明）；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记，禁止伪报。

## 3. Phase 47 Goal

目标：**Real Runner Closure Archive & Global Consistency GA**，完成 8 个
Goal：

1. 真实执行门禁 Linux Runner 收敛 v13（执行/归档/登记）
2. 跨云一阶段全球统一（任意拓扑自动仲裁）
3. 动态下推强化学习（在线决策 + 反馈闭环）
4. TSO 全球统一时钟（量子/卫星授时原型）
5. 自治无人值守监管级审计（时间戳证书）
6. TiKV 真实跨机基准定期回归 + 趋势告警
7. 真实凭据网络验证 v5（S3/Spot，TD-076 剩余项）
8. v3.0 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v13

目标：执行或如实登记遗留门禁，归档执行记录与趋势报表。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075/078；
- 门禁收敛表 v13：每项状态 / 阻塞原因 / 预期消除阶段（JVM 级扩展 +
  执行记录归档 + 趋势报表）；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0241 Real Runner Gate Convergence v13`。

### Goal 2 — 跨云一阶段全球统一

目标：跨云一阶段从固定拓扑升级为任意拓扑自动仲裁。

交付：

- `transaction/async/GlobalUnifiedOnePhaseArbitration`：任意云 × 区
   拓扑自动发现 + 动态仲裁（多数云 + 多数区）+ 回退 2PC；
- 与 MultiCloudOnePhaseScaleOut / MultiCloudOnePhaseCommit /
   AsyncCommitCoordinator / resolved-ts 联动；
- 验收：任意拓扑矩阵 + 动态仲裁矩阵 + 回退矩阵 + 幂等。

ADR：`ADR-0242 Global Unified One-Phase Arbitration`。

### Goal 3 — 动态下推强化学习

目标：动态下推从 EWMA 决策升级为强化学习在线决策。

交付：

- `sql/coprocessor/ReinforcementPushdownAgent`：状态（历史统计）→
   动作（下推/不下推）→ 奖励（耗时/传输节省）→ Q 更新；
- 与 DynamicPushdownPlanner / SqlExecutor 联动；
- 验收：RL 决策矩阵 + 收敛矩阵 + 与上层 SQL 一致（语义层不变）。

ADR：`ADR-0243 Reinforcement Learning Dynamic Pushdown`。

### Goal 4 — TSO 全球统一时钟（量子/卫星授时原型）

目标：全球统一时钟升级为量子/卫星授时原型。

交付：

- `transaction/tso/QuantumSatelliteTimeSource`：量子/卫星授时源抽象 +
   校准 + 单调 + 防回拨；
- 与 CrossCloudTsoArbitration / GlobalTsoClock / resolved-ts /
   事务协调器联动；
- 验收：授时原型矩阵 + 校准矩阵 + 单调性 + 容灾联动。

ADR：`ADR-0244 Global TSO Unified Clock (Quantum/Satellite)`。

### Goal 5 — 自治无人值守监管级审计

目标：合规审计从摘要签名升级为监管级（时间戳证书）。

交付：

- `cluster/scheduler/RegulatoryComplianceCertificate`：时间戳证书 +
   密钥轮换 + 外部审计验证；
- 与 AutonomousComplianceAuditor / AutonomousPdUnattended /
   自治控制器联动；
- 验收：证书矩阵 + 轮换矩阵 + 验证矩阵 + 熔断。

ADR：`ADR-0245 Regulatory-Grade Compliance Audit`。

### Goal 6 — TiKV 真实跨机基准定期回归 + 趋势告警

目标：跨机回归从登记升级为定期执行 + 趋势告警。

交付：

- `benchmarks/ProductionBaseline` 扩展定期回归执行器（多机部署 +
   快照 + 趋势 + 告警阈值）；
- 指标：GET/SET P50/P95/P99、吞吐、内存、RTT/RTO/RPO；
- 验收：回归矩阵 + 趋势告警矩阵 + 对比口径注明（跨机 Runner
   可执行项全绿，未执行项精确登记）。

ADR：`ADR-0246 TiKV Cross-Machine Regression & Real Credentials v5`。

### Goal 7 — 真实凭据网络验证 v5

目标：S3/Spot 真实网络凭据验证（TD-076 剩余项）。

交付：

- `config/CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 +
   权限 + 配额校验 + 失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 验收：探测矩阵 + 降级切换 + 失败登记（真实网络项如实登记）。

ADR：`ADR-0246`。

### Goal 8 — v3.0 冻结与发布流水线

目标：v3.0.0 GA 候选。

交付：

- `release.yml` 扩展 v3.0.0 标签 + Phase47BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v3.0.0-release-notes.md`。

ADR：`ADR-0247 v3.0 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0241 | Real Runner Gate Convergence v13 |
| ADR-0242 | Global Unified One-Phase Arbitration |
| ADR-0243 | Reinforcement Learning Dynamic Pushdown |
| ADR-0244 | Global TSO Unified Clock (Quantum/Satellite) |
| ADR-0245 | Regulatory-Grade Compliance Audit |
| ADR-0246 | TiKV Cross-Machine Regression & Real Credentials v5 |
| ADR-0247 | v3.0 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=550 tests**（Phase 47，surefire 口径）；

Phase 1-47 全量目标：**>=11053 tests**（当前 10503）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v13（JVM 级扩展） | 45 |
| 全球统一仲裁 | 80 |
| RL 动态下推 | 90 |
| 量子/卫星授时原型 | 80 |
| 监管级审计 | 80 |
| 生产基线 + 凭据 v5 | 95 |
| v3.0 发布/门禁 | 55 |
| 参数化边缘矩阵 | 25 |

## 7. Documentation Deliverables

```text
docs/review/phase47-real-runner-closure-archive-review.md
docs/deployment/gate-convergence-v13.md
docs/transaction/global-unified-one-phase-arbitration.md
docs/sql/reinforcement-learning-pushdown.md
docs/transaction/quantum-satellite-tso-clock.md
docs/cluster/regulatory-compliance-audit.md
docs/benchmark/tikv-cross-machine-regression-alerting.md
docs/deployment/real-credentials-validation-v5.md
docs/benchmark/phase47-production-report.md
docs/release/v3.0.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.9 冻结协议不变；v3.0 扩展 additive；
- 跨云一阶段必须回退 2PC 兜底；
- RL 下推只改决策层，语义层与上层 SQL 结果一致；
- TSO 全球统一时钟必须单调且防时钟回拨；
- 监管级审计必须可验证、可导出、可轮换；
- 基准必须如实记录（本地进程内 / 跨机口径注明）；
- 凭据探测失败必须降级 + 登记；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase47-real-runner-closure-archive-ga`

Commits：

```text
docs: add phase47 ADRs 0241-0247
feat(gates): real runner convergence v13 jvm extensions
feat(transaction): global unified one phase arbitration
feat(sql): reinforcement learning dynamic pushdown
feat(transaction): quantum satellite tso clock prototype
feat(cluster): regulatory grade compliance audit
feat(benchmark): tikv cross machine regression alerting and credential probe v5
feat(ci): v3.0 release and gate convergence v13
docs: phase47 release
```

Merge：`merge: integrate Phase47 real runner closure archive and global consistency GA`

Checkpoint：`checkpoint-before-phase47` / `checkpoint-after-phase47`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v13（可执行项全绿，未执行项精确登记，执行记录归档）
✅ 跨云一阶段全球统一（任意拓扑自动仲裁 + 回退 2PC + 幂等）
✅ RL 动态下推（在线决策 + 反馈闭环，语义层与上层 SQL 一致）
✅ TSO 量子/卫星授时原型（校准 + 单调 + 防回拨 + 容灾联动）
✅ 监管级审计（时间戳证书 + 轮换 + 外部验证 + 熔断）
✅ TiKV 跨机基准定期回归 + 趋势告警（跨机 Runner 可执行项全绿 / 登记）
✅ 真实凭据网络验证 v5（S3/Spot 探测 + 降级登记，TD-076 剩余项）
✅ v3.0.0 GA 候选（release.yml 执行/就绪）
✅ 全量回归 >=11053，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 48+，不在本阶段范围）

- 真实 Runner 门禁全量闭环 + 发布记录归档（跨地域趋势报表）
- 跨云一阶段全球统一（多组织联邦仲裁）
- RL 下推多智能体（跨查询协同决策）
- TSO 量子/卫星授时真实接入（硬件原型）
- 监管级审计（法规自动映射 + 证据链）
- TiKV 真实跨机基准（跨地域 Runner 定期回归 + 趋势告警闭环）
