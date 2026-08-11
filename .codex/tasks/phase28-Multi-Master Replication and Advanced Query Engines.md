# Phase 28 Task Prompt — Multi-Master Replication & Advanced Query Engines

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
```

当前基线：

```text
develop   : 9cdc384 merge: integrate Phase27 multi-region replication and enterprise integration
定位      : Enterprise-ready Distributed Database（v1.1.0 方向）
Tests     : 2965/2965 PASS（另 6 项容器门控本地跳过）
新能力    : 复制管道（ASYNC/SYNC）、Geo 决策日志、RBAC 守卫、
            PITR 保留、CDC 多组、SQL/Vector/SaaS 原型
```

Phase 27 交付了单向复制、Geo 事务与探索原型。Phase 28 把这些能力推向
生产形态：**双向复制（多主）与冲突解决、容灾拓扑、完整 SQL/向量引擎
生产化、SaaS 多租户落地**，同时完成 v1.1.0 冻结与发布、跨地域真实
基准。

## 2. Release 前置项（Phase 25–27 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.0.0-rc1 → v1.1.0）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域复制/Geo 事务 RTT 口径基准 | Phase 27 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0 冻结协议不变，破坏性扩展必须走 ADR-0103 兼容评审；
- 单向复制路径（Phase 27）保持零回退，双向能力 additive。

## 3. Phase 28 Goal

目标：**Multi-Master Replication & Advanced Query Engines**，完成 8 个
Goal：

1. 双向复制与 CRDT 冲突解决（多主）
2. 两地三中心容灾拓扑与演练
3. SQL 引擎进阶（JOIN / 聚合 / 优化器）
4. HNSW 生产化与混合检索
5. SaaS 多租户控制平面落地
6. RBAC RPC 帧级令牌（协议扩展）
7. v1.1.0 冻结与跨地域生产基准
8. 可观测性与容灾混沌演练

## 4. Goals

### Goal 1 — 双向复制与 CRDT 冲突解决

目标：多主写入合并，冲突可收敛。

架构：

```text
Region A（主） ⇄ ReplicationPipeline ⇄ Region B（主）
                    ↓
             CRDT 合并（LWW/计数器/集合）
```

交付：

- `replication/crdt/`：LwwRegister / GCounter / GSet / ORSet + 合并器；
- `BidirectionalPipeline`：双向投递 + 版本向量（VersionVector）因果检测；
- 冲突策略：LWW（时间戳+节点优先级）默认，可配置 CRDT 类型；
- 环回抑制：已见事件不重复应用（版本向量过滤）。

ADR：`ADR-0114 Bidirectional Replication and CRDT`。

### Goal 2 — 两地三中心容灾拓扑

目标：容灾拓扑与切换演练。

交付：

- `dr/`：DrTopology（primary/secondary/observers）、DrSwitchPlanner、
  DrDrillRunner；
- 拓扑：Region A（主）+ Region B（备）+ Region C（仲裁/只读）；
- 切换：计划内切换（决策日志补放）+ 故障切换（RPO 由复制模式决定）；
- 演练：`DrChaosTest`（主区故障 → 备区接管 → 数据一致性校验）。

ADR：`ADR-0115 Disaster Recovery Topology`。

### Goal 3 — SQL 引擎进阶

目标：从只读子集升级为可查询引擎。

交付：

- `sql/`：JOIN（两表 hash join）、聚合（COUNT/SUM/AVG/GROUP BY 子集）、
  谓词下推（key 范围推导）；
- 执行计划：`ExplainPlan`（scan/filter/join/aggregate 节点）；
- 安全：SQL 仅 READ 权限域可执行（RBAC 联动）；
- 基准：JOIN 1K×1K 延迟、聚合 100K 行吞吐（如实记录）。

ADR：`ADR-0116 SQL Query Engine`。

### Goal 4 — HNSW 生产化与混合检索

目标：向量检索从暴力基线升级为索引检索。

交付：

- `vector/hnsw/`：HNSW 原型（层级图 + 贪心搜索）、批量构建；
- `HybridSearch`：向量 + 标量过滤（复用 SQL 谓词）；
- 召回率/延迟对比（HNSW vs 暴力，如实记录）；
- 与 CDC 联动：向量变更流自动增量。

ADR：`ADR-0117 HNSW and Hybrid Search`。

### Goal 5 — SaaS 多租户控制平面落地

目标：多租户从配额原型升级为控制平面。

交付：

- `saas/`：TenantRegistry、租户级 TieringKVCluster 生成（Operator 联动）、
  租户隔离校验（存储/网络命名空间）；
- 审计日志：创建/扩容/备份/删除全记录；
- 配额动态调整与告警。

ADR：`ADR-0118 SaaS Multi-Tenant Control Plane`。

### Goal 6 — RBAC RPC 帧级令牌

目标：权限校验下沉到 RPC 帧。

交付：

- `RpcFrame` 扩展：可选令牌字段（版本化，兼容无令牌旧帧）；
- `MultiRaftEndpoint`：令牌校验 + RpcPermissionGuard 接线；
- 未认证帧仅允许 AUTH 类消息；
- v1 兼容：旧客户端无令牌帧按当前策略（配置化放行/拒绝）。

ADR：`ADR-0119 RPC Frame Token and v1.1 Release`。

### Goal 7 — v1.1.0 冻结与跨地域生产基准

目标：v1.1.0 发布候选。

交付：

- `release.yml` 触发 v1.1.0-rc1 → v1.1.0；
- 跨地域基准（Linux Runner）：复制 RTT 口径、Geo 事务延迟、容灾切换
  RTO/RPO（如实记录）；
- `docs/benchmark/phase28-production-report.md`。

### Goal 8 — 可观测性与容灾混沌

交付：

- 指标：replication_lag、crdt_conflicts、dr_rpo、sql_query_p99、
  vector_recall、tenant_active；
- `DrChaosTest` / `BidirectionalChaosTest`：分区、主备切换、双主并发
  写、环回风暴。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0114 | Bidirectional Replication and CRDT |
| ADR-0115 | Disaster Recovery Topology |
| ADR-0116 | SQL Query Engine |
| ADR-0117 | HNSW and Hybrid Search |
| ADR-0118 | SaaS Multi-Tenant Control Plane |
| ADR-0119 | RPC Frame Token and v1.1 Release |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 28）；

Phase 1-28 全量目标：**>=3200 tests**（当前 2965）。

| Module | Count |
| --- | ---: |
| 双向复制 / CRDT | 60 |
| 容灾拓扑 / 切换 | 40 |
| SQL JOIN/聚合/优化 | 40 |
| HNSW / 混合检索 | 30 |
| SaaS 多租户 | 25 |
| RPC 帧令牌 | 25 |
| 可观测性 / DR 混沌 | 20 |
| 跨地域基准 | 10 |

## 7. Documentation Deliverables

```text
docs/review/phase28-multi-master-review.md
docs/multi-region/bidirectional-replication.md
docs/multi-region/crdt-design.md
docs/dr/topology-guide.md
docs/dr/dr-drill-report.md
docs/sql/engine-design.md
docs/vector/hnsw-report.md
docs/saas/multi-tenant-guide.md
docs/api/rpc-token-guide.md
docs/benchmark/phase28-production-report.md
docs/release/v1.1.0-release-notes.md
```

## 8. Engineering Rules

- v1.0 冻结协议不变；RPC 帧令牌为版本化扩展，旧帧兼容可配置；
- 单向复制零回退（Phase 27 基准保持）；
- 双向冲突必须收敛（CRDT 数学性质 + 混沌验证），不允许双主分裂；
- 容灾演练真实执行（CI Runner），RTO/RPO 如实记录；
- SQL/HNSW/SaaS 生产化以基准与召回率为验收，不隐藏失败项；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase28-multi-master-query`

Commits：

```text
docs: ADR-0114~0119
feat(replication): bidirectional pipeline and crdt
feat(dr): topology and switch planner
feat(sql): join aggregate optimizer
feat(vector): hnsw hybrid search
feat(saas): multi-tenant control plane
feat(security): rpc frame token
feat(ci): v1.1 release and cross-region benchmark
docs: phase28 release
```

Merge：`merge: integrate Phase28 multi-master and query engines`

Checkpoint：`checkpoint-before-phase28` / `checkpoint-after-phase28`

## 10. Success Criteria

全部满足：

```text
✅ 双向复制（版本向量 + CRDT 收敛，无环回风暴）
✅ 两地三中心容灾（计划/故障切换 + DR 演练报告）
✅ SQL JOIN/聚合/优化器（基准达标）
✅ HNSW + 混合检索（召回率/延迟对比报告）
✅ SaaS 多租户控制平面（租户隔离 + 审计）
✅ RPC 帧级令牌（v1 兼容）
✅ v1.1.0 发布候选（release.yml 执行）
✅ 跨地域生产基准（RTT/RTO/RPO 如实记录）
✅ 全量回归 >=3200，单向路径零回退
```

## 11. 后续方向（Phase 29+，不在本阶段范围）

- 分布式 SQL 执行（跨 Region join）
- 向量索引生产集群化（分片 + 重平衡）
- Geo CRDT 大规模验证与校准
- SaaS 计费/市场控制面
- 三地五中心与全球一致性读
