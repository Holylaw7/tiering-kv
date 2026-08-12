# Phase 42 Task Prompt — Execution Convergence & Transaction Depth

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
```

当前基线：

```text
develop   : d5705ec merge: integrate Phase41 real integration convergence and production hardening
定位      : Enterprise-ready Distributed Database（v2.4.0）
Tests     : 7855/7855 PASS（另 6 项容器门控本地跳过）
新能力    : 真实 S3、Spot 真实数据源、密钥轮换、对象生命周期、leveled 计划、
            PD 等价调度
```

Phase 41 完成真实集成原型。Phase 42 把系统推向**执行收敛与事务深度**：
真实执行门禁 Linux Runner 收敛 v8、leveled compaction 执行接线、
悲观事务、async commit + resolved-ts、Coprocessor SQL 下推、自治 PD
调度与拓扑自发现，并完成 v2.5 冻结与发布流水线。

## 2. Release 前置项（Phase 25–41 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.4）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–41 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-066/069/072/075 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 38/39/40/41 登记，待 Runner |
| TD-076 | S3/Spot 为客户端抽象，真实凭据/网络未验证 | Phase 41 登记 |
| TD-077 | leveled compaction 为计划器原型，未接入实际执行 | Phase 41 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（新增路径 additive）；
- v1.0–v2.4 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- leveled 执行必须零回退（SSTable 格式兼容）；
- 悲观事务 / async commit 不得破坏现有 2PC 语义；
- Coprocessor 下推必须与现有 SQL 结果一致；
- 自治调度只调策略，禁止放宽一致性约束；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 42 Goal

目标：**Execution Convergence & Transaction Depth**，完成 8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v8
2. Leveled Compaction 执行接线（TD-077 关闭方向）
3. 悲观事务（Pessimistic Transaction）
4. Async Commit + Resolved Timestamp
5. Coprocessor SQL 下推
6. 自治 PD 调度（调度与自治闭环联动）
7. 全球多活自动拓扑自发现
8. v2.5 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v8

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075；
- 门禁收敛表 v8：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0206 Real Runner Gate Convergence v8`。

### Goal 2 — Leveled Compaction 执行接线

目标：leveled 计划器接入实际 Compaction 执行（TD-077 关闭方向）。

交付：

- `storage/compaction/LeveledCompactionExecutor`：计划 → 实际合并
   （latest wins + tombstone + TTL 清理）→ 层级文件落盘；
- 与 LeveledCompactionPlanner / Compaction 联动；
- 零回退：SSTable 格式兼容；
- 验收：执行矩阵 + 层级落盘 + 兼容性。

ADR：`ADR-0207 Leveled Compaction Execution`。

### Goal 3 — 悲观事务

目标：乐观 2PC 之外新增悲观路径（提前加锁 + 冲突检测）。

交付：

- `transaction/pessimistic/PessimisticTransaction`：BEGIN → 提前
   Lock → 读已锁键可见性 → COMMIT/ROLLBACK；
- 与 LockTable / TransactionParticipant 联动；
- 验收：锁冲突矩阵 + 读写可见性 + 死锁超时。

ADR：`ADR-0208 Pessimistic Transactions`。

### Goal 4 — Async Commit + Resolved Timestamp

目标：两阶段提交优化为一阶段（async commit）+ resolved-ts 服务。

交付：

- `transaction/async/AsyncCommitCoordinator`：单区事务一阶段提交 +
   回退 2PC；
- `transaction/async/ResolvedTimestampService`：跨区 resolved-ts
   推进 + 查询；
- 验收：一阶段矩阵 + 回退矩阵 + resolved-ts 单调性。

ADR：`ADR-0209 Async Commit & Resolved Timestamp`。

### Goal 5 — Coprocessor SQL 下推

目标：SQL 算子下推到存储层执行（过滤/投影/聚合）。

交付：

- `sql/coprocessor/CoprocessorRequest`：算子 + 范围 + 谓词；
- `sql/coprocessor/CoprocessorExecutor`：存储层执行 → 结果集；
- 与 SqlExecutor / 分布式执行联动；
- 验收：下推结果与上层 SQL 一致 + 谓词矩阵。

ADR：`ADR-0210 Coprocessor SQL Pushdown`。

### Goal 6 — 自治 PD 调度

目标：PD 调度器与自治闭环联动（自动均衡 + 容量建议执行）。

交付：

- `cluster/scheduler/AutonomousPdScheduler`：调度计划 → 护栏内执行
   （epoch + 限幅 + 回滚）；
- 与 Placement/Rebalance/Quota Scheduler + 自治控制器联动；
- 验收：调度执行矩阵 + 护栏矩阵 + 回滚。

ADR：`ADR-0211 Autonomous Scheduling & Topology Discovery`。

### Goal 7 — 全球多活自动拓扑自发现

目标：地域拓扑自动发现（节点注册 → 分组 → 传播）。

交付：

- `cluster/topology/TopologyDiscovery`：节点心跳 → 拓扑推断（地域/
   可用区/延迟分组）；
- 与 TopologyFederatedAutonomy / PlacementScheduler 联动；
- 验收：发现矩阵 + 分组正确性 + 故障节点剔除。

ADR：`ADR-0211`。

### Goal 8 — v2.5 冻结与发布流水线

目标：v2.5.0 发布候选。

交付：

- `release.yml` 扩展 v2.5.0 标签 + Phase42BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.5.0-release-notes.md`。

ADR：`ADR-0212 v2.5 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0206 | Real Runner Gate Convergence v8 |
| ADR-0207 | Leveled Compaction Execution |
| ADR-0208 | Pessimistic Transactions |
| ADR-0209 | Async Commit & Resolved Timestamp |
| ADR-0210 | Coprocessor SQL Pushdown |
| ADR-0211 | Autonomous Scheduling & Topology Discovery |
| ADR-0212 | v2.5 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=500 tests**（Phase 42，surefire 口径）；

Phase 1-42 全量目标：**>=8355 tests**（当前 7855）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v8（JVM 级扩展） | 40 |
| Leveled 执行 | 70 |
| 悲观事务 | 80 |
| Async Commit + resolved-ts | 75 |
| Coprocessor 下推 | 70 |
| 自治 PD 调度 | 70 |
| 拓扑自发现 | 60 |
| v2.5 发布/门禁 | 40 |

## 7. Documentation Deliverables

```text
docs/review/phase42-execution-transaction-review.md
docs/deployment/gate-convergence-v8.md
docs/storage/leveled-execution.md
docs/transaction/pessimistic.md
docs/transaction/async-commit.md
docs/sql/coprocessor-pushdown.md
docs/cluster/autonomous-scheduling.md
docs/cluster/topology-discovery.md
docs/benchmark/phase42-production-report.md
docs/release/v2.5.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.4 冻结协议不变；新能力 additive；
- leveled 执行必须零回退（SSTable 格式兼容）；
- 悲观事务 / async commit 不得破坏现有 2PC 语义；
- Coprocessor 下推必须与上层 SQL 结果一致；
- 自治调度只调策略，禁止放宽一致性约束；
- 拓扑自发现必须故障节点剔除；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase42-execution-convergence-transaction-depth`

Commits：

```text
docs: ADR-0206~0212
feat(gates): real runner convergence v8 jvm extensions
feat(storage): leveled compaction execution
feat(transaction): pessimistic transactions
feat(transaction): async commit and resolved timestamp
feat(sql): coprocessor pushdown
feat(cluster): autonomous pd scheduling
feat(cluster): topology discovery
feat(ci): v2.5 release and gate convergence v8
docs: phase42 release
```

Merge：`merge: integrate Phase42 execution convergence and transaction depth`

Checkpoint：`checkpoint-before-phase42` / `checkpoint-after-phase42`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v8（可执行项全绿，未执行项精确登记）
✅ Leveled Compaction 执行（计划 → 合并 → 层级落盘，零回退）
✅ 悲观事务（提前加锁 + 冲突 + 死锁超时）
✅ Async Commit + resolved-ts（一阶段 + 回退 + 单调性）
✅ Coprocessor SQL 下推（与上层结果一致）
✅ 自治 PD 调度（护栏内执行 + 回滚）
✅ 全球拓扑自发现（分组 + 故障剔除）
✅ v2.5.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=8355，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 43+，不在本阶段范围）

- 真实 Runner 全量门禁闭环（3 连绿 + 发布记录）
- async commit 规模化（跨区一阶段）
- Coprocessor 多算子联合下推（filter + project + aggregate）
- 自治 PD 与全球自治联动（动态拓扑学习）
- 事务 TSO 集群化（全局时间戳服务）
- 生产级 benchmark 基线（对比 TiKV 口径）
