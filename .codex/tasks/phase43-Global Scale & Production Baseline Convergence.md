# Phase 43 Task Prompt — Global Scale & Production Baseline Convergence

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
```

当前基线：

```text
develop   : 48d8556 merge: integrate Phase42 execution convergence and transaction depth
定位      : Enterprise-ready Distributed Database（v2.5.0）
Tests     : 8357/8357 PASS（另 6 项容器门控本地跳过）
新能力    : Leveled 执行、悲观事务、Async Commit、Coprocessor、自治 PD、拓扑发现
```

Phase 42 完成事务深度与执行收敛。Phase 43 把系统推向**全球规模与生产
基线收敛**：真实执行门禁 Linux Runner 收敛 v9、跨区一阶段提交、多算子
联合下推、TSO 集群化、自治 PD 与全球自治联动、生产级 Benchmark 基线
（对比 TiKV 口径）、真实凭据验证，并完成 v2.6 冻结与发布流水线。

## 2. Release 前置项（Phase 25–42 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.5）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–42 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-066/069/072/075/078 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 38/39/40/41/42 登记，待 Runner |
| TD-076 | S3/Spot 为客户端抽象，真实凭据/网络未验证 | Phase 41 登记 |
| TD-079 | async commit 为单区一阶段，跨区一阶段未做 | Phase 42 登记 |
| TD-080 | Coprocessor 为单算子下推，多算子联合未做 | Phase 42 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v2.5 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 跨区一阶段必须回退 2PC 兜底；
- 多算子下推必须与上层 SQL 结果一致；
- TSO 必须单调且可恢复；
- 自治联动只调策略，禁止放宽一致性约束；
- Benchmark 基线必须如实记录（对比口径注明）；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 43 Goal

目标：**Global Scale & Production Baseline Convergence**，完成 8 个
Goal：

1. 真实执行门禁 Linux Runner 收敛 v9
2. 跨区一阶段提交（TD-079 关闭方向）
3. Coprocessor 多算子联合下推（TD-080 关闭方向）
4. TSO 集群化（全局时间戳服务）
5. 自治 PD 与全球自治联动
6. 生产级 Benchmark 基线（对比 TiKV 口径）
7. 真实凭据验证（S3/Spot，TD-076 关闭方向）
8. v2.6 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v9

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075/078；
- 门禁收敛表 v9：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0213 Real Runner Gate Convergence v9`。

### Goal 2 — 跨区一阶段提交

目标：async commit 从单区扩展到跨区一阶段（TD-079 关闭方向）。

交付：

- `transaction/async/CrossRegionOnePhaseCommit`：跨区主副本一阶段
   提交 + 失败回退 2PC；
- 与 AsyncCommitCoordinator / resolved-ts 联动；
- 验收：一阶段矩阵 + 回退矩阵 + 幂等。

ADR：`ADR-0214 Cross-Region One-Phase Commit`。

### Goal 3 — Coprocessor 多算子联合下推

目标：单算子升级为 FILTER + PROJECT + AGGREGATE 联合下推
（TD-080 关闭方向）。

交付：

- `sql/coprocessor/CompoundCoprocessorRequest`：算子链（filter →
   project → aggregate）；
- 与 CoprocessorExecutor / SqlExecutor 联动；
- 验收：算子链矩阵 + 与上层 SQL 一致。

ADR：`ADR-0215 Multi-Operator Coprocessor Pushdown`。

### Goal 4 — TSO 集群化

目标：全局时间戳服务（单调 + 批量分配 + 恢复）。

交付：

- `transaction/tso/TsoService`：批量分配 + 单调推进 + 恢复不回退；
- 与 resolved-ts / 事务协调器联动；
- 验收：分配矩阵 + 单调性 + 恢复不回退。

ADR：`ADR-0216 TSO Cluster Service`。

### Goal 5 — 自治 PD 与全球自治联动

目标：调度计划接入全球自治闭环（动态拓扑学习）。

交付：

- `cluster/scheduler/GlobalAutonomyPdIntegration`：拓扑变化 → 调度
   计划 → 护栏内执行；
- 与 AutonomousPdScheduler / TopologyDiscovery / 自治控制器联动；
- 验收：联动矩阵 + 护栏 + 回滚。

ADR：`ADR-0217 Autonomous PD & Global Autonomy Integration`。

### Goal 6 — 生产级 Benchmark 基线

目标：建立与 TiKV 可对比的基准口径（延迟/吞吐/内存）。

交付：

- `benchmarks/ProductionBaseline`：A/B/C 三级基线（内存引擎 / 服务端 /
   全链路）+ 对比表；
- 指标：GET/SET P50/P95/P99、吞吐、内存；
- 验收：基线矩阵 + 对比口径注明（本地进程内）。

ADR：`ADR-0218 Production Benchmark Baseline & Real Credentials`。

### Goal 7 — 真实凭据验证

目标：S3/Spot 真实端点凭据验证（TD-076 关闭方向）。

交付：

- `config/CredentialProbe`：S3/Spot 端点连通性 + 凭据探测（模拟/真实
   可切换）；
- 与 S3ObjectStorage / SpotMarketDataSource 联动；
- 验收：探测矩阵 + 降级切换 + 失败登记。

ADR：`ADR-0218`。

### Goal 8 — v2.6 冻结与发布流水线

目标：v2.6.0 发布候选。

交付：

- `release.yml` 扩展 v2.6.0 标签 + Phase43BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.6.0-release-notes.md`。

ADR：`ADR-0219 v2.6 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0213 | Real Runner Gate Convergence v9 |
| ADR-0214 | Cross-Region One-Phase Commit |
| ADR-0215 | Multi-Operator Coprocessor Pushdown |
| ADR-0216 | TSO Cluster Service |
| ADR-0217 | Autonomous PD & Global Autonomy Integration |
| ADR-0218 | Production Benchmark Baseline & Real Credentials |
| ADR-0219 | v2.6 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=510 tests**（Phase 43，surefire 口径）；

Phase 1-43 全量目标：**>=8867 tests**（当前 8357）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v9（JVM 级扩展） | 40 |
| 跨区一阶段 | 70 |
| 多算子下推 | 75 |
| TSO 集群 | 75 |
| 自治 PD 联动 | 70 |
| 生产基线 + 凭据探测 | 80 |
| v2.6 发布/门禁 | 50 |
| 参数化边缘矩阵 | 50 |

## 7. Documentation Deliverables

```text
docs/review/phase43-scale-baseline-review.md
docs/deployment/gate-convergence-v9.md
docs/transaction/cross-region-one-phase.md
docs/sql/multi-operator-pushdown.md
docs/transaction/tso-cluster.md
docs/cluster/autonomous-integration.md
docs/benchmark/production-baseline.md
docs/deployment/real-credentials-validation.md
docs/benchmark/phase43-production-report.md
docs/release/v2.6.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.5 冻结协议不变；新能力 additive；
- 跨区一阶段必须回退 2PC 兜底；
- 多算子下推必须与上层 SQL 结果一致；
- TSO 必须单调且可恢复（重启不回退）；
- 自治联动只调策略，禁止放宽一致性约束；
- 基准必须如实记录（本地进程内口径注明）；
- 凭据探测失败必须降级 + 登记；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase43-global-scale-production-baseline`

Commits：

```text
docs: ADR-0213~0219
feat(gates): real runner convergence v9 jvm extensions
feat(transaction): cross region one phase commit
feat(sql): multi operator coprocessor pushdown
feat(transaction): tso cluster service
feat(cluster): autonomous pd global integration
feat(benchmark): production baseline and credential probe
feat(ci): v2.6 release and gate convergence v9
docs: phase43 release
```

Merge：`merge: integrate Phase43 global scale and production baseline convergence`

Checkpoint：`checkpoint-before-phase43` / `checkpoint-after-phase43`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v9（可执行项全绿，未执行项精确登记）
✅ 跨区一阶段提交（回退 2PC 兜底 + 幂等）
✅ 多算子联合下推（FILTER+PROJECT+AGGREGATE 与上层一致）
✅ TSO 集群化（批量分配 + 单调 + 恢复不回退）
✅ 自治 PD 与全球自治联动（护栏 + 回滚）
✅ 生产级 Benchmark 基线（A/B/C + 对比 TiKV 口径）
✅ 真实凭据验证（S3/Spot 探测 + 降级登记，TD-076 关闭方向）
✅ v2.6.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=8867，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 44+，不在本阶段范围）

- 真实 Runner 全量门禁闭环（3 连绿 + 发布记录）
- async commit 规模化（全局一阶段）
- Coprocessor 全算子下推（JOIN/GROUP BY 下推）
- TSO 跨地域容灾（多地时间戳服务）
- 自治 PD 全自动（无人工审批的受限自治）
- 生产级 benchmark 对比 TiKV 真实基线（跨机 Runner）
