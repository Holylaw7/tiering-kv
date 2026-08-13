# Phase 49 Task Prompt — Real Runner Closure Archive & Cross-Regulatory Federation

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
Phase 47   : 门禁收敛 v13 + 全球统一仲裁 + RL 动态下推 + 量子/卫星授时
             + 监管级合规证书 + TiKV 跨机回归告警 + 真实凭据 v5
             + v3.0 GA 发布流水线
Phase 48   : 门禁收敛 v14 + 多组织联邦仲裁 + RL 多智能体 + 量子/卫星
             硬件适配 + 法规自动映射 + TiKV 回归闭环 + 真实凭据 v6
             + v3.1 发布流水线
```

当前基线：

```text
develop   : da725c1 merge: integrate Phase48 real runner closure and multi-organization federation
定位      : Enterprise-ready Distributed Database（v3.1.0 发布候选）
Tests     : 11635/11635 PASS（另 6 项容器门控本地跳过）
新能力    : 门禁收敛 v14 + 发布归档、多组织联邦仲裁、RL 多智能体、
            量子/卫星硬件适配、法规自动映射、TiKV 回归闭环、
            真实凭据延迟握手 v6
```

Phase 48 完成真实 Runner 门禁全量闭环与多组织联邦一致性。Phase 49 把
系统推向**真实 Runner 闭环归档与跨监管域联邦**：真实执行门禁收敛 v15
（全量闭环归档）、多组织联邦仲裁规模化（跨监管域）、RL 多智能体联邦
学习（隐私保护协同决策）、TSO 量子/卫星授时真实硬件接入（商用设备
接口）、监管法规库 + 差异报告、TiKV 真实跨机基准定期回归 + 趋势告警
闭环归档、真实凭据网络验证 v7，并完成 v3.2 冻结与发布流水线。

## 2. Release 前置项（Phase 25–48 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行（v14 登记） |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行（v14 登记） |
| K8S-001 | kind 集群内验证 | 脚本就绪，待执行（v14 登记） |
| REL-001 | release.yml（v1.1–v3.1）真实运行记录 | 流水线就绪，待触发（v14 登记） |
| BM-001 | 跨机 Production Benchmark | 本地口径完成，跨机待 Runner（v14 登记） |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间 | 进程内完成，跨机待 Runner（v14 登记） |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner（v14 登记） |
| TD-066/069/072/075/078 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机） | 登记完成，待 Runner（v14 登记） |
| TD-076 | S3/Spot 真实凭据/网络验证（延迟握手 JVM 已绿） | 真实网络待 Runner（v14 登记） |
| TD-079 | 多组织联邦（JVM 已绿）→ Phase 49 跨监管域 | JVM 完成，跨监管域待做 |
| TD-080 | RL 多智能体（JVM 已绿）→ Phase 49 联邦学习 | JVM 完成，联邦学习待做 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v3.1 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 跨监管域一阶段必须回退 2PC 兜底；
- 联邦学习只改决策层，语义层与上层 SQL 结果一致，且隐私不泄漏；
- TSO 商用设备接入必须单调且防时钟回拨；
- 法规库/差异报告必须可验证、可导出、可轮换；
- Benchmark 基线必须如实记录（对比口径注明）；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记，禁止伪报。

## 3. Phase 49 Goal

目标：**Real Runner Closure Archive & Cross-Regulatory Federation**，
完成 8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v15（全量闭环归档）
2. 多组织联邦仲裁规模化（跨监管域）
3. RL 多智能体联邦学习（隐私保护协同决策）
4. TSO 量子/卫星授时真实硬件接入（商用设备接口）
5. 监管法规库 + 差异报告
6. TiKV 真实跨机基准定期回归 + 趋势告警闭环归档
7. 真实凭据网络验证 v7（S3/Spot，TD-076 剩余项）
8. v3.2 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v15

目标：执行或如实登记遗留门禁，全量闭环归档。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075/078；
- 门禁收敛表 v15：每项状态 / 阻塞原因 / 预期消除阶段（JVM 级扩展 +
  全量闭环归档 + 跨地域趋势报表）；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0255 Real Runner Gate Convergence v15`。

### Goal 2 — 多组织联邦仲裁规模化（跨监管域）

目标：联邦仲裁从组织级扩展到跨监管域。

交付：

- `transaction/async/CrossRegulatoryFederationArbitration`：监管域边界
   发现 + 域级仲裁 + 跨域一阶段 + 回退 2PC；
- 与 MultiOrgFederationArbitration / GlobalUnifiedOnePhaseArbitration /
   AsyncCommitCoordinator / resolved-ts 联动；
- 验收：跨监管域矩阵 + 域级仲裁矩阵 + 回退矩阵 + 幂等。

ADR：`ADR-0256 Cross-Regulatory Federation Scale-out`。

### Goal 3 — RL 多智能体联邦学习

目标：多智能体从加权聚合升级为隐私保护联邦学习。

交付：

- `sql/coprocessor/FederatedPushdownLearning`：本地 Q 更新 + 模型聚合
   （FedAvg）+ 隐私保护（噪声注入/梯度裁剪）；
- 与 MultiAgentPushdownCoordinator / ReinforcementPushdownAgent /
   SqlExecutor 联动；
- 验收：联邦学习矩阵 + 隐私矩阵 + 收敛 + 与上层 SQL 一致。

ADR：`ADR-0257 Federated Learning Multi-Agent Pushdown`。

### Goal 4 — TSO 量子/卫星授时真实硬件接入（商用设备接口）

目标：硬件适配从模拟升级为商用设备接口。

交付：

- `transaction/tso/CommercialTimeDeviceConnector`：商用设备连接/读取/
   健康检查 + 驱动 SPI + 模拟回退；
- 与 QuantumSatelliteHardwareAdapter / CrossCloudTsoArbitration /
   resolved-ts / 事务协调器联动；
- 验收：设备矩阵 + 连接矩阵 + 单调性 + 容灾联动。

ADR：`ADR-0258 Commercial Quantum/Satellite TSO Integration`。

### Goal 5 — 监管法规库 + 差异报告

目标：法规映射升级为法规库 + 差异报告。

交付：

- `cluster/scheduler/RegulatoryKnowledgeBase`：法规版本化存储 +
   条款差异计算 + 差异报告生成；
- 与 RegulatoryMappingEngine / RegulatoryComplianceCertificate /
   自治控制器联动；
- 验收：法规库矩阵 + 差异矩阵 + 报告矩阵 + 验证。

ADR：`ADR-0259 Regulatory Knowledge Base & Diff Reporting`。

### Goal 6 — TiKV 真实跨机基准定期回归 + 趋势告警闭环归档

目标：跨机回归从闭环升级为归档（趋势报表 + 告警历史）。

交付：

- `benchmarks/ProductionBaseline` 扩展回归归档执行器（多机部署 +
   快照 + 趋势 + 告警 + 归档报表）；
- 指标：GET/SET P50/P95/P99、吞吐、内存、RTT/RTO/RPO；
- 验收：回归归档矩阵 + 趋势报表矩阵 + 对比口径注明（跨机 Runner
   可执行项全绿，未执行项精确登记）。

ADR：`ADR-0260 TiKV Regression Archive & Real Credentials v7`。

### Goal 7 — 真实凭据网络验证 v7

目标：S3/Spot 真实网络凭据验证（TD-076 剩余项）。

交付：

- `config/CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 +
   权限 + 配额 + 延迟 + 抖动 + 失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 验收：探测矩阵 + 降级切换 + 失败登记（真实网络项如实登记）。

ADR：`ADR-0260`。

### Goal 8 — v3.2 冻结与发布流水线

目标：v3.2.0 发布候选。

交付：

- `release.yml` 扩展 v3.2.0 标签 + Phase49BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v3.2.0-release-notes.md`。

ADR：`ADR-0261 v3.2 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0255 | Real Runner Gate Convergence v15 |
| ADR-0256 | Cross-Regulatory Federation Scale-out |
| ADR-0257 | Federated Learning Multi-Agent Pushdown |
| ADR-0258 | Commercial Quantum/Satellite TSO Integration |
| ADR-0259 | Regulatory Knowledge Base & Diff Reporting |
| ADR-0260 | TiKV Regression Archive & Real Credentials v7 |
| ADR-0261 | v3.2 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=570 tests**（Phase 49，surefire 口径）；

Phase 1-49 全量目标：**>=12205 tests**（当前 11635）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v15（JVM 级扩展） | 45 |
| 跨监管域联邦 | 80 |
| 联邦学习下推 | 90 |
| 商用设备接入 | 80 |
| 法规库/差异报告 | 80 |
| 生产基线 + 凭据 v7 | 100 |
| v3.2 发布/门禁 | 55 |
| 参数化边缘矩阵 | 40 |

## 7. Documentation Deliverables

```text
docs/review/phase49-real-runner-closure-archive-review.md
docs/deployment/gate-convergence-v15.md
docs/transaction/cross-regulatory-federation.md
docs/sql/federated-learning-pushdown.md
docs/transaction/commercial-time-device-integration.md
docs/cluster/regulatory-knowledge-base.md
docs/benchmark/tikv-regression-archive.md
docs/deployment/real-credentials-validation-v7.md
docs/benchmark/phase49-production-report.md
docs/release/v3.2.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v3.1 冻结协议不变；v3.2 扩展 additive；
- 跨监管域一阶段必须回退 2PC 兜底；
- 联邦学习只改决策层，语义层与上层 SQL 结果一致，隐私不泄漏；
- TSO 商用设备接入必须单调且防时钟回拨；
- 法规库/差异报告必须可验证、可导出、可轮换；
- 基准必须如实记录（本地进程内 / 跨机口径注明）；
- 凭据探测失败必须降级 + 登记；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase49-real-runner-closure-archive-cross-regulatory`

Commits：

```text
docs: add phase49 ADRs 0255-0261
feat(gates): real runner convergence v15 jvm extensions
feat(transaction): cross regulatory federation arbitration
feat(sql): federated learning multi agent pushdown
feat(transaction): commercial quantum satellite time device connector
feat(cluster): regulatory knowledge base and diff reporting
feat(benchmark): tikv regression archive and credential probe v7
feat(ci): v3.2 release and gate convergence v15
docs: phase49 release
```

Merge：`merge: integrate Phase49 real runner closure archive and cross-regulatory federation`

Checkpoint：`checkpoint-before-phase49` / `checkpoint-after-phase49`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v15（可执行项全绿，未执行项精确登记，闭环归档）
✅ 跨监管域联邦仲裁（域级仲裁 + 回退 2PC + 幂等）
✅ RL 联邦学习（FedAvg + 隐私保护，语义层与上层 SQL 一致）
✅ TSO 商用设备接入（设备 SPI + 连接 + 单调 + 防回拨）
✅ 法规库 + 差异报告（版本化 + 差异计算 + 可验证）
✅ TiKV 跨机回归归档（快照 + 趋势 + 告警历史 + 报表）
✅ 真实凭据网络验证 v7（S3/Spot 探测 + 降级登记，TD-076 剩余项）
✅ v3.2.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=12205，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 50+，不在本阶段范围）

- 真实 Runner 门禁全量闭环归档（3 连绿 + 发布记录 + 趋势报表）
- 跨监管域联邦规模化（全球法规网络）
- 联邦学习安全聚合（同态加密/安全多方计算）
- TSO 商用设备真实接入（多厂商驱动）
- 法规库自动更新（法规源订阅）
- TiKV 真实跨机基准（跨地域 Runner 定期回归 + 趋势告警闭环归档）
