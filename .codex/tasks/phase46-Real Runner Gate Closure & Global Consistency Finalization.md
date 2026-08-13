# Phase 46 Task Prompt — Real Runner Gate Closure & Global Consistency Finalization

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
```

当前基线：

```text
develop   : 3bae262 merge: integrate Phase45 real runner closure v11 and multi-cloud global consistency
定位      : Enterprise-ready Distributed Database（v2.8.0 发布候选）
Tests     : 9951/9951 PASS（另 6 项容器门控本地跳过）
新能力    : 门禁收敛 v11、跨云全局一阶段、多表 JOIN/窗口函数下推、
            TSO 全球统一时钟、自治 PD 无人值守、TiKV 跨机对比基线、
            真实凭据认证握手 v3
```

Phase 45 完成真实 Runner 闭环 v11 与多云全球一致性。Phase 46 把系统
推向**真实 Runner 门禁闭环与全球一致性最终化**：真实执行门禁收敛 v12、
跨云一阶段规模化、窗口函数全族 / 动态下推、TSO 跨云授时仲裁与防时钟
回拨、自治无人值守全自动合规证明、TiKV 真实跨机基准定期回归与真实
凭据网络验证 v4，并完成 v2.9 冻结与发布流水线。

## 2. Release 前置项（Phase 25–45 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行（v11 登记） |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行（v11 登记） |
| K8S-001 | kind 集群内验证 | 脚本就绪，待执行（v11 登记） |
| REL-001 | release.yml（v1.1–v2.8）真实运行记录 | 流水线就绪，待触发（v11 登记） |
| BM-001 | 跨机 Production Benchmark | 本地口径完成，跨机待 Runner（v11 登记） |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间 | 进程内完成，跨机待 Runner（v11 登记） |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner（v11 登记） |
| TD-066/069/072/075/078 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机） | 登记完成，待 Runner（v11 登记） |
| TD-076 | S3/Spot 真实凭据/网络验证（认证握手 JVM 已绿） | 真实网络待 Runner（v11 登记） |
| TD-079 | 跨云一阶段（JVM 已绿）→ Phase 46 规模化 | JVM 完成，规模化待做 |
| TD-080 | 多表 JOIN/窗口下推（JVM 已绿）→ Phase 46 全族/动态 | JVM 完成，扩展待做 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v2.8 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 跨云一阶段必须回退 2PC 兜底；
- 窗口函数全族 / 动态下推必须与上层 SQL 结果一致；
- TSO 跨云授时仲裁必须单调且防时钟回拨；
- 自治无人值守保留熔断入口与外部审计接入，禁止放宽一致性约束；
- Benchmark 基线必须如实记录（对比口径注明）；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记，禁止伪报。

## 3. Phase 46 Goal

目标：**Real Runner Gate Closure & Global Consistency Finalization**，
完成 8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v12
2. 跨云一阶段规模化（多云多区混合拓扑）
3. 窗口函数全族 / 动态下推（运行时成本感知）
4. TSO 跨云授时仲裁 + 防时钟回拨
5. 自治无人值守全自动合规证明（外部审计接入）
6. TiKV 真实跨机基准定期回归
7. 真实凭据网络验证 v4（S3/Spot，TD-076 剩余项）
8. v2.9 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v12

目标：执行或如实登记遗留门禁，交付物继续完善。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075/078；
- 门禁收敛表 v12：每项状态 / 阻塞原因 / 预期消除阶段（JVM 级扩展 +
  交付物完善，未执行项继续精确登记）；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0234 Real Runner Gate Convergence v12`。

### Goal 2 — 跨云一阶段规模化

目标：跨云一阶段从单云组扩展到多云多区混合拓扑。

交付：

- `transaction/async/MultiCloudOnePhaseScaleOut`：云 × 区混合拓扑
   （多区多云）主副本资格 + 分层仲裁（云级 + 区级）；
- 与 MultiCloudOnePhaseCommit / GlobalOnePhaseCommit /
   AsyncCommitCoordinator / resolved-ts 联动；
- 验收：混合拓扑矩阵 + 分层仲裁矩阵 + 回退矩阵 + 幂等。

ADR：`ADR-0235 Multi-Cloud One-Phase Scale-out`。

### Goal 3 — 窗口函数全族 / 动态下推

目标：窗口函数从 ROW_NUMBER/RANK 扩展到全族，并加入运行时动态下推。

交付：

- `sql/coprocessor/CompoundCoprocessorRequest` 扩展窗口函数全族
   （LAG / LEAD / SUM OVER / COUNT OVER / AVG OVER）；
- `CoprocessorExecutor` 扩展窗口算子执行；
- `DynamicPushdownPlanner`：运行时成本感知（历史执行统计 → 下推
   决策），供 SqlExecutor 选择下推计划；
- 验收：窗口全族矩阵 + 动态决策矩阵 + 与上层 SQL 一致。

ADR：`ADR-0236 Window Function Family & Dynamic Pushdown`。

### Goal 4 — TSO 跨云授时仲裁 + 防时钟回拨

目标：全球统一时钟升级为跨云授时仲裁，防时钟回拨。

交付：

- `transaction/tso/CrossCloudTsoArbitration`：跨云授时源仲裁（多数云
   时间共识）+ 回拨保护（单调计数器 + 最大回拨窗口）；
- 与 GlobalTsoClock / TsoDisasterRecovery / resolved-ts / 事务协调器
   联动；
- 验收：仲裁矩阵 + 回拨保护矩阵 + 单调性 + 容灾联动。

ADR：`ADR-0237 Cross-Cloud TSO Arbitration & Clock Rollback Protection`。

### Goal 5 — 自治无人值守全自动合规证明

目标：合规报告升级为全自动合规证明，接入外部审计。

交付：

- `cluster/scheduler/AutonomousComplianceAuditor`：全自动合规证明
   （策略合规校验 + 审计链签名）+ 外部审计接口；
- 与 AutonomousPdUnattended / AutonomousPdFullAutomation /
   TopologyDiscovery / 自治控制器联动；
- 验收：合规矩阵 + 签名矩阵 + 审计接入 + 熔断。

ADR：`ADR-0238 Autonomous Unattended Compliance Automation`。

### Goal 6 — TiKV 真实跨机基准定期回归

目标：跨机基准从登记升级为定期回归（可执行项执行 / 未执行项登记）。

交付：

- `benchmarks/ProductionBaseline` 扩展定期回归脚本（多机部署 +
  对比表快照 + 趋势记录）；
- 指标：GET/SET P50/P95/P99、吞吐、内存、RTT/RTO/RPO；
- 验收：回归矩阵 + 对比口径注明（跨机 Runner 可执行项全绿，
  未执行项精确登记）。

ADR：`ADR-0239 TiKV Cross-Machine Benchmark Regression & Real Credentials v4`。

### Goal 7 — 真实凭据网络验证 v4

目标：S3/Spot 真实网络凭据验证（TD-076 剩余项）。

交付：

- `config/CredentialProbe` 扩展真实网络握手矩阵（可达性 + 认证 +
   权限校验 + 失败登记 + 自动降级）；
- 与 S3ObjectStorage / SpotMarketDataSource / 密钥轮换联动；
- 验收：探测矩阵 + 降级切换 + 失败登记（真实网络项如实登记）。

ADR：`ADR-0239`。

### Goal 8 — v2.9 冻结与发布流水线

目标：v2.9.0 发布候选。

交付：

- `release.yml` 扩展 v2.9.0 标签 + Phase46BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.9.0-release-notes.md`。

ADR：`ADR-0240 v2.9 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0234 | Real Runner Gate Convergence v12 |
| ADR-0235 | Multi-Cloud One-Phase Scale-out |
| ADR-0236 | Window Function Family & Dynamic Pushdown |
| ADR-0237 | Cross-Cloud TSO Arbitration & Clock Rollback Protection |
| ADR-0238 | Autonomous Unattended Compliance Automation |
| ADR-0239 | TiKV Cross-Machine Benchmark Regression & Real Credentials v4 |
| ADR-0240 | v2.9 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=540 tests**（Phase 46，surefire 口径）；

Phase 1-46 全量目标：**>=10491 tests**（当前 9951）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v12（JVM 级扩展） | 45 |
| 跨云一阶段规模化 | 80 |
| 窗口全族 / 动态下推 | 90 |
| 跨云授时仲裁 / 防回拨 | 80 |
| 自治合规自动化 | 75 |
| 生产基线 + 凭据 v4 | 90 |
| v2.9 发布/门禁 | 55 |
| 参数化边缘矩阵 | 25 |

## 7. Documentation Deliverables

```text
docs/review/phase46-real-runner-closure-review.md
docs/deployment/gate-convergence-v12.md
docs/transaction/multi-cloud-one-phase-scale-out.md
docs/sql/window-function-family-dynamic-pushdown.md
docs/transaction/cross-cloud-tso-arbitration.md
docs/cluster/autonomous-unattended-compliance.md
docs/benchmark/tikv-cross-machine-regression.md
docs/deployment/real-credentials-validation-v4.md
docs/benchmark/phase46-production-report.md
docs/release/v2.9.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.8 冻结协议不变；新能力 additive；
- 跨云一阶段必须回退 2PC 兜底；
- 窗口全族 / 动态下推必须与上层 SQL 结果一致；
- TSO 跨云仲裁必须单调且防时钟回拨；
- 自治无人值守保留熔断与外部审计，禁止放宽一致性约束；
- 基准必须如实记录（本地进程内 / 跨机口径注明）；
- 凭据探测失败必须降级 + 登记；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase46-real-runner-closure-global-finalization`

Commits：

```text
docs: add phase46 ADRs 0234-0240
feat(gates): real runner convergence v12 jvm extensions
feat(transaction): multi cloud one phase scale-out
feat(sql): window function family and dynamic pushdown
feat(transaction): cross cloud tso arbitration and rollback protection
feat(cluster): autonomous unattended compliance automation
feat(benchmark): tikv cross machine regression and credential probe v4
feat(ci): v2.9 release and gate convergence v12
docs: phase46 release
```

Merge：`merge: integrate Phase46 real runner gate closure and global consistency finalization`

Checkpoint：`checkpoint-before-phase46` / `checkpoint-after-phase46`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v12（可执行项全绿，未执行项精确登记）
✅ 跨云一阶段规模化（云×区混合拓扑 + 分层仲裁 + 回退 2PC + 幂等）
✅ 窗口函数全族 / 动态下推（LAG/LEAD/SUM/COUNT/AVG OVER 与上层一致）
✅ TSO 跨云授时仲裁 + 防时钟回拨（单调 + 容灾联动）
✅ 自治无人值守全自动合规证明（签名 + 外部审计接入 + 熔断）
✅ TiKV 真实跨机基准定期回归（跨机 Runner 可执行项全绿 / 登记）
✅ 真实凭据网络验证 v4（S3/Spot 探测 + 降级登记，TD-076 剩余项）
✅ v2.9.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=10491，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 47+，不在本阶段范围）

- 真实 Runner 全量门禁闭环归档（3 连绿 + 发布记录 + 趋势报表）
- 跨云一阶段全球统一（任意拓扑自动仲裁）
- 动态下推强化学习（在线决策模型 + 反馈闭环）
- TSO 全球统一时钟（跨云授时 + 量子/卫星授时原型）
- 自治无人值守（全自动合规证明 + 监管级审计）
- TiKV 真实跨机基准（跨地域 Runner 定期回归 + 趋势告警）
