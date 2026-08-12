# Phase 44 Task Prompt — Real Runner Gate Closure & Global Scale Finalization

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
```

当前基线：

```text
develop   : 17c4449 merge: integrate Phase43 global scale and production baseline convergence
定位      : Enterprise-ready Distributed Database（v2.6.0 发布候选）
Tests     : 8892/8892 PASS（另 6 项容器门控本地跳过）
新能力    : 跨区一阶段、多算子联合下推、TSO 集群化、自治 PD 联动、
            生产级基线（A/B/C）、S3/Spot 凭据探测、门禁收敛表 v9
```

Phase 43 完成全球规模与生产基线收敛。Phase 44 把系统推向**真实执行门禁
闭环与全球规模最终化**：真实 Runner 门禁 v10、全局一阶段规模化、全算子
联合下推、TSO 跨地域容灾、自治 PD 全自动（受限）、TiKV 对比基线 +
真实凭据 v2，并完成 v2.7 冻结与发布流水线。

## 2. Release 前置项（Phase 25–43 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.6）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-066/069/072/075/078 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | 登记完成，待 Runner |
| TD-076 | S3/Spot 真实凭据/网络验证（JVM 探测已绿） | 真实网络待 Runner |
| TD-079 | 跨区一阶段（JVM 已绿）→ Phase 44 规模化 | JVM 完成，规模化待做 |
| TD-080 | 多算子下推（JVM 已绿）→ Phase 44 全算子 | JVM 完成，全算子待做 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v2.6 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 全局一阶段必须回退 2PC 兜底；
- 全算子下推必须与上层 SQL 结果一致；
- TSO 跨地域主备切换单调可恢复，切换不回退；
- 自治全自动保留人工熔断入口，禁止放宽一致性约束；
- Benchmark 基线必须如实记录（对比口径注明）；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记，禁止伪报。

## 3. Phase 44 Goal

目标：**Real Runner Gate Closure & Global Scale Finalization**，完成 8 个
Goal：

1. 真实执行门禁 Linux Runner 收敛 v10
2. 全局一阶段提交规模化（TD-079 规模化方向）
3. Coprocessor 全算子联合下推（TD-080 规模化方向）
4. TSO 跨地域容灾
5. 自治 PD 全自动（受限自治）
6. 生产级 Benchmark 对比 TiKV 真实口径
7. 真实凭据验证 v2（S3/Spot，TD-076 关闭方向）
8. v2.7 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v10

目标：执行或如实登记遗留门禁，交付物继续完善。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075/078；
- 门禁收敛表 v10：每项状态 / 阻塞原因 / 预期消除阶段（JVM 级扩展 +
  交付物完善，未执行项继续精确登记）；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0220 Real Runner Gate Convergence v10`。

### Goal 2 — 全局一阶段提交规模化

目标：跨区一阶段从主副本资格扩展到多区域全局一阶段（TD-079 规模化）。

交付：

- `transaction/async/GlobalOnePhaseCommit`：3 地 / 5 地主副本资格 →
   全局一阶段 + 任一区域失败回退 2PC；
- 与 CrossRegionOnePhaseCommit / AsyncCommitCoordinator / resolved-ts
   联动；
- 验收：规模化矩阵 + 回退矩阵 + 幂等 + resolved-ts 联动。

ADR：`ADR-0221 Global One-Phase Commit Scale-out`。

### Goal 3 — Coprocessor 全算子联合下推

目标：单算子链升级为 JOIN / GROUP BY / ORDER BY / LIMIT 全算子下推
（TD-080 规模化）。

交付：

- `sql/coprocessor/CompoundCoprocessorRequest` 扩展 JOIN / GROUP BY /
   ORDER BY / LIMIT 算子；
- 与 CoprocessorExecutor / SqlExecutor 联动；
- 验收：全算子链矩阵 + 与上层 SQL 一致。

ADR：`ADR-0222 Full Operator Coprocessor Pushdown`。

### Goal 4 — TSO 跨地域容灾

目标：全局时间戳服务支持主备部署 + 故障切换 + 恢复不回退。

交付：

- `transaction/tso/TsoDisasterRecovery`：主备 TSO + 切换 + 水位同步 +
   恢复不回退；
- 与 TsoService / resolved-ts / 事务协调器联动；
- 验收：主备矩阵 + 切换矩阵 + 恢复不回退。

ADR：`ADR-0223 Cross-Region TSO Disaster Recovery`。

### Goal 5 — 自治 PD 全自动（受限自治）

目标：调度计划从人工审批升级为无人工审批的受限自治。

交付：

- `cluster/scheduler/AutonomousPdFullAutomation`：风险分级 → 护栏内
   自动执行 → 自动回滚 → 人工熔断入口；
- 与 GlobalAutonomyPdIntegration / AutonomousPdScheduler /
   TopologyDiscovery / 自治控制器联动；
- 验收：自动执行矩阵 + 护栏 + 熔断 + 回滚。

ADR：`ADR-0224 Autonomous PD Full Automation`。

### Goal 6 — 生产级 Benchmark 对比 TiKV 真实口径

目标：建立与 TiKV 可对比的基准口径（延迟/吞吐/内存），扩展 D 级
分布式全链路。

交付：

- `benchmarks/ProductionBaseline` 扩展 D 级（分布式全链路）+ TiKV
   对比表（公开口径 vs 本地进程内 vs 跨机待执行）；
- 指标：GET/SET P50/P95/P99、吞吐、内存；
- 验收：基线矩阵 + 对比口径注明（本地进程内 + 跨机待 Runner）。

ADR：`ADR-0225 Production Benchmark TiKV Comparison & Real Credentials v2`。

### Goal 7 — 真实凭据验证 v2

目标：S3/Spot 真实端点凭据验证（TD-076 关闭方向）。

交付：

- `config/CredentialProbe` 扩展真实 HTTP 探针 + 失败登记 + 自动降级；
- 与 S3ObjectStorage / SpotMarketDataSource 联动；
- 验收：探测矩阵 + 降级切换 + 失败登记。

ADR：`ADR-0225`。

### Goal 8 — v2.7 冻结与发布流水线

目标：v2.7.0 发布候选。

交付：

- `release.yml` 扩展 v2.7.0 标签 + Phase44BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.7.0-release-notes.md`。

ADR：`ADR-0226 v2.7 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0220 | Real Runner Gate Convergence v10 |
| ADR-0221 | Global One-Phase Commit Scale-out |
| ADR-0222 | Full Operator Coprocessor Pushdown |
| ADR-0223 | Cross-Region TSO Disaster Recovery |
| ADR-0224 | Autonomous PD Full Automation |
| ADR-0225 | Production Benchmark TiKV Comparison & Real Credentials v2 |
| ADR-0226 | v2.7 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=520 tests**（Phase 44，surefire 口径）；

Phase 1-44 全量目标：**>=9412 tests**（当前 8892）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v10（JVM 级扩展） | 45 |
| 全局一阶段规模化 | 75 |
| 全算子下推 | 80 |
| TSO 跨地域容灾 | 80 |
| 自治 PD 全自动 | 75 |
| 生产基线 + 凭据 v2 | 85 |
| v2.7 发布/门禁 | 55 |
| 参数化边缘矩阵 | 25 |

## 7. Documentation Deliverables

```text
docs/review/phase44-real-runner-gate-closure-review.md
docs/deployment/gate-convergence-v10.md
docs/transaction/global-one-phase-commit.md
docs/sql/full-operator-pushdown.md
docs/transaction/tso-disaster-recovery.md
docs/cluster/autonomous-pd-full-automation.md
docs/benchmark/tikv-comparison-baseline.md
docs/deployment/real-credentials-validation-v2.md
docs/benchmark/phase44-production-report.md
docs/release/v2.7.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.6 冻结协议不变；新能力 additive；
- 全局一阶段必须回退 2PC 兜底；
- 全算子下推必须与上层 SQL 结果一致；
- TSO 跨地域必须单调且恢复不回退；
- 自治全自动只调策略、保留人工熔断入口，禁止放宽一致性约束；
- 基准必须如实记录（本地进程内口径注明）；
- 凭据探测失败必须降级 + 登记；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase44-real-runner-gate-closure`

Commits：

```text
docs: add phase44 ADRs 0220-0226
feat(gates): real runner convergence v10 jvm extensions
feat(transaction): global one phase commit scale-out
feat(sql): full operator coprocessor pushdown
feat(transaction): cross region tso disaster recovery
feat(cluster): autonomous pd full automation
feat(benchmark): tikv comparison baseline and credential probe v2
feat(ci): v2.7 release and gate convergence v10
docs: phase44 release
```

Merge：`merge: integrate Phase44 real runner gate closure and global scale finalization`

Checkpoint：`checkpoint-before-phase44` / `checkpoint-after-phase44`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v10（可执行项全绿，未执行项精确登记）
✅ 全局一阶段规模化（3 地/5 地 + 回退 2PC 兜底 + 幂等 + resolved-ts）
✅ 全算子联合下推（JOIN/GROUP BY/ORDER BY/LIMIT 与上层一致）
✅ TSO 跨地域容灾（主备切换 + 单调 + 恢复不回退）
✅ 自治 PD 全自动（护栏 + 熔断 + 回滚 + 审计）
✅ 生产级 Benchmark 基线（A/B/C/D + TiKV 对比口径）
✅ 真实凭据验证 v2（S3/Spot 探测 + 降级登记，TD-076 关闭方向）
✅ v2.7.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=9412，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 45+，不在本阶段范围）

- 真实 Runner 全量门禁闭环（3 连绿 + 发布记录）
- 全局一阶段跨云（多云协调器 + 仲裁）
- Coprocessor 多表 JOIN / 窗口函数 / 下推成本模型
- TSO 全球统一时钟（GPS/原子钟/NTP 混合授时）
- 自治 PD 无人值守（无人工审批 + 合规证明自动化）
- TiKV 真实跨机对比基准（跨地域 Runner）
