# Phase 30 Task Prompt — Dynamic Resharding & Global Operations

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
```

当前基线：

```text
develop   : 5e14e80 merge: integrate Phase29 distributed query and geo scale validation
定位      : Enterprise-ready Distributed Database（v1.2.0）
Tests     : 3471/3471 PASS（另 6 项容器门控本地跳过）
新能力    : 分布式 SQL、向量分片、CRDT 规模模拟、五中心全球读、计量/市场、告警
```

Phase 29 的分布式能力多为进程内等价与计划生成。Phase 30 完成
**生产闭环**：动态重分片（在线扩容）、向量迁移真实落地、SQL 写事务
（触发 2PC）、全球读水位与真实复制联动、SaaS 账单导出，并执行
跨地域真实基准与 v1.3.0 冻结。

## 2. Release 前置项（Phase 25–29 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1/v1.2）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO 真实基准 | Phase 27–29 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0/v1.1/v1.2 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 单向/双向复制、分布式 SQL/向量零回退，新能力 additive；
- 动态重分片必须在线（写不中断、路由可回滚）。

## 3. Phase 30 Goal

目标：**Dynamic Resharding & Global Operations**，完成 8 个 Goal：

1. 动态重分片（在线扩容：分片拆分/合并 + 数据迁移）
2. 向量分片迁移落地（真实迁移 + 查询不中断）
3. SQL 写事务（SQL 触发 2PC：BEGIN/COMMIT + 谓词路由）
4. 全球读水位联动（真实复制水位 + 陈旧度 SLA）
5. SaaS 账单导出与周期结算
6. v1.3.0 冻结与跨地域生产基准
7. 分布式查询优化（谓词下推深化 + 结果缓存）
8. 全链路混沌与容量模型

## 4. Goals

### Goal 1 — 动态重分片

目标：在线扩容/缩容，写不中断。

架构：

```text
ShardRouter（版本化路由）
  → ReshardPlanner（拆分/合并计划）
  → ShardMigration（数据迁移 + 路由切换 + 回滚）
```

交付：

- `sharding/`：ShardRouter（routing version + epoch）、ReshardPlanner、
  ShardMigration（迁移游标复用 Migration 能力）；
- 拆分：单分片 → 多分片；合并：多分片 → 单分片；
- 双写窗口 + 原子路由切换 + 失败回滚；
- 验收：扩容期间写入不中断、路由版本单调、回滚无数据丢失。

ADR：`ADR-0126 Dynamic Resharding`。

### Goal 2 — 向量分片迁移落地

目标：Phase 29 重平衡计划 → 真实迁移执行。

交付：

- `vector/cluster/`：ShardMigrationExecutor（逐 id 迁移 + 校验）；
- 迁移期间双写 + 查询路由版本；
- 完成校验（目标分片计数）后原子切换；
- 验收：迁移后 totalSize 一致、查询召回保持。

ADR：`ADR-0127 Vector Shard Migration`。

### Goal 3 — SQL 写事务

目标：SQL 从只读升级为写事务（触发 2PC）。

交付：

- `sql/txn/`：BeginStatement / CommitStatement / WriteStatement 解析；
- `SqlTxnExecutor`：BEGIN → SET/DELETE（路由 Region）→ COMMIT
  （复用 GeoTransactionCoordinator / 2PC）；
- RBAC：WRITE 权限域校验（ADR-0110）；
- 验收：单/跨 Region 写事务正确性 + 回滚安全。

ADR：`ADR-0128 SQL Write Transactions`。

### Goal 4 — 全球读水位联动

目标：GlobalReadRouter 与真实复制管道联动。

交付：

- 水位来源：复制管道（Phase 27）/ 双向 CRDT（Phase 28）已应用水位；
- `GlobalReadRouter` 接入水位提供者（Supplier<Long>）；
- 陈旧度 SLA：bounded 模式读陈旧度分位报告（p50/p95/p99）；
- 验收：水位滞后触发告警（Phase 29 AlertManager 联动）。

ADR：`ADR-0129 Global Read Watermark Integration`。

### Goal 5 — SaaS 账单导出与周期结算

目标：计量 → 周期结算 → 账单文件。

交付：

- `saas/billing/`：BillingPeriod、Invoice（行项目 + 总价）、
  InvoiceExporter（CSV/JSON）；
- 周期结算：快照 → 冻结 → 导出 → 审计关联（TenantAuditLog）；
- 验收：计量矩阵 × 周期参数化结算正确。

ADR：`ADR-0130 Billing Export and Period Settlement`。

### Goal 6 — v1.3.0 冻结与跨地域生产基准

目标：v1.3.0 发布候选 + 真实跨地域数据。

交付：

- `release.yml` 扩展 v1.3.0 标签；
- 跨地域基准（Linux Runner）：动态重分片迁移、SQL 写事务、全球读
  陈旧度、五中心 RTO/RPO（如实记录）；
- `docs/benchmark/phase30-production-report.md`。

ADR：`ADR-0131 v1.3 Freeze and Cross-Region Benchmark`。

### Goal 7 — 分布式查询优化

交付：

- 谓词下推深化：WHERE 条件 → 分片计划（key 范围裁剪）；
- 结果缓存：查询缓存（queryId + 水位失效）；
- `ExplainPlan` 扩展：分片执行计划节点；
- 验收：范围裁剪减少扫描量、缓存命中延迟基准。

### Goal 8 — 全链路混沌与容量模型

交付：

- 地域级故障矩阵：单地域、双地域、仲裁丢失、重分片中断；
- `ReshardChaosTest` / `SqlTxnChaosTest` / `BillingChaosTest`；
- 容量模型：`CapacityPlanner`（节点 × 分片 × 流量 → 资源估算）；
- 输出：容量报告与混沌报告。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0126 | Dynamic Resharding |
| ADR-0127 | Vector Shard Migration |
| ADR-0128 | SQL Write Transactions |
| ADR-0129 | Global Read Watermark Integration |
| ADR-0130 | Billing Export and Period Settlement |
| ADR-0131 | v1.3 Freeze and Cross-Region Benchmark |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 30）；

Phase 1-30 全量目标：**>=3700 tests**（当前 3471）。

| Module | Count |
| --- | ---: |
| 动态重分片 | 55 |
| 向量迁移 | 35 |
| SQL 写事务 | 45 |
| 全球读水位 | 30 |
| 账单导出/结算 | 30 |
| v1.3 发布/跨地域基准 | 20 |
| 查询优化 | 20 |
| 混沌/容量 | 15 |

## 7. Documentation Deliverables

```text
docs/review/phase30-resharding-ops-review.md
docs/sharding/resharding-guide.md
docs/vector/shard-migration.md
docs/sql/write-transactions.md
docs/dr/global-read-watermark.md
docs/saas/billing-export.md
docs/observability/capacity-model.md
docs/benchmark/phase30-production-report.md
docs/release/v1.3.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.2 冻结协议不变；新能力 additive；
- 动态重分片必须在线：写不中断、路由版本单调、失败回滚；
- SQL 写事务必须经 2PC/元数据决策（禁止绕过事务状态机）；
- 跨地域数据如实记录（拓扑/RTT/分位/陈旧度），与单地域口径分离；
- 账单结算以参数化矩阵验收，不隐藏失败项；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase30-resharding-global-ops`

Commits：

```text
docs: ADR-0126~0131
feat(sharding): dynamic resharding
feat(vector): shard migration execution
feat(sql): write transactions
feat(dr): global read watermark integration
feat(saas): billing export and settlement
feat(ci): v1.3 release and cross-region benchmark
docs: phase30 release
```

Merge：`merge: integrate Phase30 dynamic resharding and global operations`

Checkpoint：`checkpoint-before-phase30` / `checkpoint-after-phase30`

## 10. Success Criteria

全部满足：

```text
✅ 动态重分片（在线拆分/合并，写不中断，路由单调，回滚安全）——已完成（ADR-0126）
✅ 向量分片迁移落地（双写 + 校验 + 原子切换，召回保持）——已完成（ADR-0127）
✅ SQL 写事务（单/跨 Region 2PC，回滚安全，RBAC）——解析/执行完成，2PC 回调待 Phase 31 端到端
✅ 全球读水位联动（真实复制水位 + 陈旧度 SLA 报告）——已完成（ADR-0129）
✅ SaaS 账单导出（周期结算 + 行项目 + 审计关联）——已完成（ADR-0130）
✅ v1.3.0 发布候选（release.yml 执行）——流水线扩展完成，执行待 Runner
✅ 分布式查询优化（谓词裁剪 + 结果缓存基准）——已完成（Goal 7）
✅ 全链路混沌与容量模型（地域故障矩阵 + 容量报告）——已完成（Goal 8）
✅ 全量回归 >=3700，复制/查询路径零回退——3742/3742 PASS（新增 271）
```

## 11. 后续方向（Phase 31+，不在本阶段范围）

- 自动重分片（负载驱动）
- 跨 Region 分布式 SQL 写 JOIN
- 全球多活全链路（Active-Active + 冲突实时合并）
- 多云/混合云部署与迁移
- 完整企业控制台（控制面 SaaS 产品化）
