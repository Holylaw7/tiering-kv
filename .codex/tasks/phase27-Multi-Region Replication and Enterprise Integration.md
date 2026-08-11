# Phase 27 Task Prompt — Multi-Region Replication & Enterprise Integration

## 1. Context

当前系统已完成：

```text
Phase 1-18 : Storage / Raft / Multi-Raft / Region / Migration / Gateway
Phase 19-23: MVCC / Percolator 2PC / 事务 RPC / 生命周期 / LockResolver / 运行时
Phase 24   : 元数据 Multi-Raft + K8s 清单 + 备份恢复 + 滚动升级 + CI E2E
Phase 25   : 元数据 Multi-Raft 网络化（TD-050 关闭）+ 混沌交付物
Phase 26   : v1 协议冻结 + PITR + CDC + 企业安全 RBAC + Operator + CLI + 发布流水线
```

当前基线：

```text
develop   : 1cb0e80 merge: integrate Phase26 v1 release freeze and enterprise readiness
定位      : Enterprise-ready Distributed Database v1.0（发布候选）
Tests     : 2701/2701 PASS（另 6 项容器门控本地跳过）
Protocol  : RESP2 / RPC v1 / 存储格式 v1 已冻结（ADR-0103）
```

Phase 26 完成了 v1 冻结与企业能力模型（PITR / CDC / RBAC / Operator），
Phase 27 把这些能力接入运行路径并启动下一形态：**跨地域复制与地理
分布式事务**，同时完成 RBAC 网关/RPC 接线、PITR 保留策略与 CDC
多消费者组等企业集成，并以探索方式评估 SQL / Vector / SaaS 方向。

## 2. Release 前置项（Phase 25/26 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.0.0-rc1）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- 不破坏 v1.0 冻结协议（新特性 additive，协议变更需 ADR-0103 兼容评审）；
- 跨地域能力必须保持单地域路径零回退（Phase 1–26 基准不回退）。

## 3. Phase 27 Goal

目标：**Multi-Region Replication & Enterprise Integration**，完成 8 个
Goal：

1. Multi-Region Replication（跨地域异步/同步复制）
2. Geo Distributed Transaction（地理分布式事务）
3. RBAC 网关/RPC 接线（企业安全落地）
4. PITR 保留策略与归档生命周期
5. CDC 多消费者组（fan-out）
6. SQL Query Layer（探索原型）
7. Vector Index（探索原型）
8. Enterprise SaaS Control Plane（探索路线图）

## 4. Goals

### Goal 1 — Multi-Region Replication

目标：单地域 → 跨地域数据复制。

架构：

```text
Region A（leader） → ReplicationPipeline → Region B（follower）
                        ↓
                   ReplicationLag / 冲突检测
```

交付：

- `replication/`：ReplicationPipeline / ReplicaState / LagTracker /
  ConflictDetector（异步模式）；
- 模式：async（默认）与 sync（quorum ack）可配置；
- 复用 CDC 事件流作为复制载体（ADR-0105），避免第二套日志；
- 冲突策略：主地域优先 + 冲突事件标记（Phase 28 深化 CRDT/双向）。

ADR：`ADR-0108 Multi-Region Replication`。

### Goal 2 — Geo Distributed Transaction

目标：跨地域事务（两地三中心形态的先导）。

架构：

```text
Coordinator → Region A（本地 2PC）
            → Region B（远程 participant，经 GeoRpcTransport）
```

交付：

- `transaction/geo/`：GeoRpcTransport / GeoRegionTxnClient /
  GeoDecisionLog；
- 决策仍走元数据 Raft（v1 语义不变），仅 participant 远程化；
- 验收：跨地域提交无丢失、无重复；区域故障时未决事务可恢复。

ADR：`ADR-0109 Geo Distributed Transaction`。

### Goal 3 — RBAC 网关/RPC 接线

目标：Security GA 模型（ADR-0106）接入真实路径。

交付：

- 网关：`AUTH <token>` 命令 + 会话绑定 Role；READ/WRITE/ADMIN 权限
  校验到命令层；
- RPC：CredentialManager 校验接入 MultiRaftEndpoint（令牌头 +
  Permission 检查）；
- 证书/TLS 延续 ADR-0055，令牌轮换支持在线生效；
- 测试：`GatewayRbacIntegrationTest` / `RpcRbacIntegrationTest`。

ADR：`ADR-0110 RBAC Gateway and RPC Integration`。

### Goal 4 — PITR 保留策略与归档生命周期

目标：PITR 归档可管理。

交付：

- `backup/pitr/RetentionPolicy`：按时间/数量保留段，删除安全水位
  （不低于最新 checkpoint）；
- `ArchiveLifecycleManager`：定时清理 + 归档完整性校验；
- 恢复语义不变（ADR-0104），删除策略不得破坏任何保留恢复点。

ADR：`ADR-0111 PITR Retention and Archive Lifecycle`。

### Goal 5 — CDC 多消费者组（fan-out）

目标：多下游独立消费。

交付：

- `cdc/ConsumerGroup`：按 group 独立 checkpoint；
- `CDCConsumerRegistry`：组注册/列表/删除；
- 单事件多组投递，组间进度隔离；
- exactly-once 语义按组保持（ADR-0105 延续）。

ADR：`ADR-0112 CDC Multi-Consumer Groups`。

### Goal 6 — SQL Query Layer（探索原型）

目标：只读 SQL 原型，不承诺完整查询引擎。

交付：

- `sql/`：Parser（SELECT/WHERE/LIMIT 子集）+ 计划器（scan/filter）；
- 执行于 Snapshot Read（MVCC readTS）；
- 范围：单表点查/范围查，JOIN/聚合列入路线图；
- 输出：`docs/sql/roadmap.md` + 基准（与原生 API 对比如实记录）。

ADR：`ADR-0113 Exploratory Layers: SQL, Vector, SaaS`。

### Goal 7 — Vector Index（探索原型）

目标：向量相似度检索原型。

交付：

- `vector/`：Embedding 存储（复用 MVCC）+ 暴力检索基线 +
  HNSW 原型（可选）；
- 与 CDC 联动（向量变更流）；
- 输出：`docs/vector/roadmap.md` + 召回率/延迟基线。

ADR：`ADR-0113`（与 SQL 共用探索 ADR）。

### Goal 8 — Enterprise SaaS Control Plane（探索路线图）

目标：多集群管理入口的路线图，不实现完整 SaaS。

交付：

- `docs/saas/roadmap.md`：多集群元数据、租户隔离、计费/配额、审计；
- `saas/`：ClusterTenant 模型 + 配额校验原型；
- 与 Operator（ADR-0107）联动：按租户生成 TieringKVCluster。

ADR：`ADR-0113`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0108 | Multi-Region Replication |
| ADR-0109 | Geo Distributed Transaction |
| ADR-0110 | RBAC Gateway and RPC Integration |
| ADR-0111 | PITR Retention and Archive Lifecycle |
| ADR-0112 | CDC Multi-Consumer Groups |
| ADR-0113 | Exploratory Layers: SQL, Vector, SaaS |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 27）；

Phase 1-27 全量目标：**>=2950 tests**（当前 2701）。

| Module | Count |
| --- | ---: |
| Multi-Region Replication | 60 |
| Geo Distributed Transaction | 50 |
| RBAC Gateway/RPC | 30 |
| PITR Retention | 30 |
| CDC Fan-out | 30 |
| SQL/Vector/SaaS 探索 | 30 |
| Final Benchmark | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase27-multi-region-review.md
docs/multi-region/replication-design.md
docs/multi-region/geo-transaction.md
docs/api/rbac-guide.md
docs/backup/pitr-retention.md
docs/cdc/fanout-design.md
docs/sql/roadmap.md
docs/vector/roadmap.md
docs/saas/roadmap.md
docs/benchmark/phase27-report.md
docs/release/v1.1.0-roadmap.md
```

## 8. Engineering Rules

- v1.0 冻结协议不变；新能力 additive；
- 单地域路径零回退：Phase 1–26 全量回归必须保持 2701 全绿基线；
- 跨地域基准如实记录（区域拓扑/RTT/模式），与单地域口径分离；
- 探索项（SQL/Vector/SaaS）明确标注 prototype/roadmap，不宣称 GA；
- 容器/Runner 测试 tag 隔离；环境受限项登记 TD；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase27-multi-region-enterprise`

Commits：

```text
docs: ADR-0108~0113
feat(replication): multi-region replication pipeline
feat(geo): geo distributed transaction
feat(security): rbac gateway and rpc wiring
feat(pitr): retention and archive lifecycle
feat(cdc): consumer groups
feat(explore): sql/vector/saas prototypes
docs: phase27 release
```

Merge：`merge: integrate Phase27 multi-region and enterprise integration`

Checkpoint：`checkpoint-before-phase27` / `checkpoint-after-phase27`

## 10. Success Criteria

全部满足：

```text
✅ Multi-Region Replication（async/sync 模式 + 冲突标记）——已完成（ADR-0108）
✅ Geo Distributed Transaction（跨地域提交无丢失无重复）——已完成（ADR-0109）
✅ RBAC 网关/RPC 接线（AUTH + 权限校验）——已完成（ADR-0110）
✅ PITR 保留策略（安全水位删除）——已完成（ADR-0111）
✅ CDC 多消费者组（组间进度隔离）——已完成（ADR-0112）
✅ SQL/Vector/SaaS 探索原型与路线图——已完成（ADR-0113）
✅ 单地域全量回归零回退——2965/2965 PASS（新增 264）
✅ v1.1.0 路线图发布——已完成
```

## 11. 后续方向（Phase 28+，不在本阶段范围）

- 双向复制与 CRDT 冲突解决
- 两地三中心/三地五中心容灾
- 完整 SQL 引擎（JOIN/聚合/优化器）
- HNSW 生产化与混合检索
- SaaS 多租户控制平面落地
