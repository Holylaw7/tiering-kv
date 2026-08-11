# Phase 31 Task Prompt — Autonomous Resharding & Global Active-Active

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
```

当前基线：

```text
develop   : 5ae1c89 merge: integrate Phase30 dynamic resharding and global operations
定位      : Enterprise-ready Distributed Database（v1.3.0）
Tests     : 3742/3742 PASS（另 6 项容器门控本地跳过）
新能力    : 动态重分片、向量迁移、SQL 写事务、全球读水位、账单导出、容量模型
```

Phase 30 完成了手动/计划驱动的重分片与运维闭环。Phase 31 把这些能力
推向**自治与全球多活**：负载驱动自动重分片、SQL 写事务端到端 2PC、
向量迁移双写联动、全球 Active-Active 全链路、账单自动滚动结算、多云
部署与企业控制台，并完成 v1.4 冻结与全球多活生产基准。

## 2. Release 前置项（Phase 25–30 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.3）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO 真实基准 | Phase 27–30 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.3 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 单向/双向复制、分布式 SQL/向量、动态重分片零回退；
- 自动重分片必须可熔断（负载异常时停止迁移，不放大故障）。

## 3. Phase 31 Goal

目标：**Autonomous Resharding & Global Active-Active**，完成 8 个 Goal：

1. 负载驱动自动重分片
2. SQL 写 2PC 端到端
3. 向量迁移双写联动
4. 全球 Active-Active 全链路
5. 账单周期滚动与自动结算
6. 多云/混合云部署与迁移
7. 企业控制台（控制面 SaaS 产品化）
8. v1.4 冻结与全球多活生产基准

## 4. Goals

### Goal 1 — 负载驱动自动重分片

目标：Phase 30 计划驱动 → 负载/容量自动触发。

交付：

- `sharding/auto/`：LoadProbe（QPS/延迟/分片大小采样）、
  AutoReshardController（阈值触发 + 熔断）、ReshardPolicy；
- 触发条件：分片 QPS/大小超阈值 → 自动拆分；低负载 → 合并；
- 熔断：连续失败/负载异常停止迁移并告警；
- 验收：参数化负载矩阵触发正确、熔断不放大故障。

ADR：`ADR-0132 Load-Driven Auto Resharding`。

### Goal 2 — SQL 写 2PC 端到端

目标：SqlTxnExecutor 的 COMMIT 回调接入真实 2PC。

交付：

- `sql/txn/SqlTxn2PcBridge`：WriteOp → TxnMessages.Mutation →
  GeoTransactionCoordinator / DistributedTxnRouter；
- BEGIN/COMMIT 生命周期与事务状态机对齐（禁止旁路）；
- 单/跨 Region 写事务端到端（RBAC WRITE 校验）；
- 验收：与原生 2PC 语义等价（提交/回滚/幂等）。

ADR：`ADR-0133 SQL Write Transaction End-to-End 2PC`。

### Goal 3 — 向量迁移双写联动

目标：ShardRouter 双写窗口与向量迁移真实联动。

交付：

- `vector/cluster/`：VectorShardRouter（版本化）+ 迁移期间双写
  （源 + 目标）→ 校验 → 原子切换；
- 查询路由版本与迁移状态一致；
- 验收：迁移窗口写入不丢失、切换后查询召回保持。

ADR：`ADR-0134 Vector Shard Double-Write Integration`。

### Goal 4 — 全球 Active-Active 全链路

目标：多地域同时读写，冲突实时合并。

交付：

- `replication/active/`：ActiveActivePipeline（双向 + 环回抑制 +
  实时 CRDT 合并）、ConflictMetrics；
- 网关：多地域写路由（地域亲和）+ 冲突事件审计；
- 全球读水位与 Active-Active 联动（Phase 30 水位源）；
- 验收：双地域并发写收敛、无环回风暴、冲突可审计。

ADR：`ADR-0135 Global Active-Active Full Chain`。

### Goal 5 — 账单周期滚动与自动结算

目标：Phase 30 手动冻结 → 周期自动滚动结算。

交付：

- `saas/billing/`：BillingScheduler（周期滚动 + 冻结 + 导出 +
  审计关联）；
- 参数化周期（月/周/自定义）；
- 验收：滚动结算矩阵正确、冻结后新用量入下周期。

ADR：`ADR-0136 Billing Rolling Settlement`（与多云共用）。

### Goal 6 — 多云/混合云部署与迁移

目标：K8s 清单多云化与集群迁移。

交付：

- `deploy/multicloud/`：云抽象（storageClass/ingress/registry 参数化）；
- `CloudMigration`：集群间数据搬迁（复用复制/迁移能力）；
- kind/托管集群双环境验证；
- 验收：清单多云参数化、跨环境迁移无丢失。

ADR：`ADR-0136`（与账单共用）。

### Goal 7 — 企业控制台

目标：控制面 SaaS 产品化原型。

交付：

- `console/`：REST API（租户/集群/账单/告警查询）、ConsoleServer、
  权限接入（RBAC，ADR-0110）；
- 自服务：租户创建集群（TenantClusterPlanner 联动）、查看账单/指标；
- 验收：API 参数化矩阵 + 权限矩阵。

ADR：`ADR-0137`（与控制台共用，含 v1.4 冻结）。

### Goal 8 — v1.4 冻结与全球多活生产基准

目标：v1.4.0 发布候选 + 全球多活数据。

交付：

- `release.yml` 扩展 v1.4.0 标签；
- 全球多活基准（Linux Runner）：双地域写吞吐、冲突率、收敛时间、
  全球读陈旧度（如实记录）；
- `docs/benchmark/phase31-production-report.md`。

ADR：`ADR-0137`（含 v1.4 冻结与全球多活基准）。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0132 | Load-Driven Auto Resharding |
| ADR-0133 | SQL Write Transaction End-to-End 2PC |
| ADR-0134 | Vector Shard Double-Write Integration |
| ADR-0135 | Global Active-Active Full Chain |
| ADR-0136 | Billing Rolling Settlement & Multi-Cloud Deployment |
| ADR-0137 | Enterprise Console & v1.4 Freeze |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 31）；

Phase 1-31 全量目标：**>=3950 tests**（当前 3742）。

| Module | Count |
| --- | ---: |
| 自动重分片 | 45 |
| SQL 写 2PC 端到端 | 40 |
| 向量双写联动 | 30 |
| 全球 Active-Active | 45 |
| 账单滚动结算 | 25 |
| 多云部署/迁移 | 25 |
| 企业控制台 | 25 |
| v1.4 发布/全球多活基准 | 15 |

## 7. Documentation Deliverables

```text
docs/review/phase31-active-active-review.md
docs/sharding/auto-resharding.md
docs/sql/write-2pc.md
docs/vector/double-write-migration.md
docs/multi-region/active-active.md
docs/saas/billing-rolling.md
docs/deployment/multicloud-guide.md
docs/console/console-api.md
docs/benchmark/phase31-production-report.md
docs/release/v1.4.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.3 冻结协议不变；新能力 additive；
- 自动重分片必须可熔断（不放大故障）；
- SQL 写必须经 2PC/元数据决策（禁止旁路事务状态机）；
- Active-Active 冲突必须收敛可审计（无环回风暴）；
- 多云/账单/控制台以参数化矩阵验收，不隐藏失败项；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase31-autonomous-active-active`

Commits：

```text
docs: ADR-0132~0137
feat(sharding): load-driven auto resharding
feat(sql): write transaction end-to-end 2pc
feat(vector): double-write migration
feat(replication): global active-active
feat(saas): billing rolling settlement
feat(deploy): multicloud deployment
feat(console): enterprise console api
feat(ci): v1.4 release and global benchmark
docs: phase31 release
```

Merge：`merge: integrate Phase31 autonomous resharding and global active-active`

Checkpoint：`checkpoint-before-phase31` / `checkpoint-after-phase31`

## 10. Success Criteria

全部满足：

```text
✅ 负载驱动自动重分片（阈值触发 + 熔断，不放大故障）
✅ SQL 写 2PC 端到端（与原生事务语义等价）
✅ 向量迁移双写联动（窗口写入不丢失，召回保持）
✅ 全球 Active-Active（双地域写收敛 + 冲突可审计）
✅ 账单周期滚动（参数化结算 + 冻结语义）
✅ 多云部署与集群迁移（清单参数化 + 无丢失）
✅ 企业控制台（REST API + RBAC + 租户自服务）
✅ v1.4.0 发布候选（release.yml 执行）
✅ 全量回归 >=3950，复制/查询/重分片路径零回退
```

## 11. 后续方向（Phase 32+，不在本阶段范围）

- 全局多活自动选主与流量调度
- 跨云数据主权（数据驻留/合规）
- 控制台多租户 SaaS 商业化（计费/市场闭环）
- AI 驱动容量规划与自动运维
- 数据网格（跨业务域联邦查询）
