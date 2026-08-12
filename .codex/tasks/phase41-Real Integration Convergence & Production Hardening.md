# Phase 41 Task Prompt — Real Integration Convergence & Production Hardening

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
```

当前基线：

```text
develop   : b45950b merge: integrate Phase40 topology-aware autonomy and object storage convergence
定位      : Enterprise-ready Distributed Database（v2.3.0）
Tests     : 7360/7360 PASS（另 6 项容器门控本地跳过）
新能力    : 拓扑联邦自治、对象存储归档、跨链互操作、Spot 竞价、学习型加固、
            在线 Pareto
```

Phase 40 完成拓扑感知自治与对象存储原型。Phase 41 把系统推向**真实集成
收敛与生产加固**：真实执行门禁 Linux Runner 收敛 v7、真实 S3 API 接入、
Spot 市场真实数据源、签名密钥轮换、对象存储生命周期联动、生产级 LSM
演进（leveled compaction + immutable MemTable 轮转）、PD 等价调度
生产化，并完成 v2.4 冻结与发布流水线。

## 2. Release 前置项（Phase 25–40 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v2.3）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–40 进程内完成，跨机待执行 |
| TD-051/054/059/060/063 | 跨地域真实 2PC/联邦/流量/自治基准 | 进程内完成，跨机待 Runner |
| TD-066/069/072 | 真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域） | Phase 38/39/40 登记，待 Runner |
| TD-068 | 签名密钥无轮换机制（HMAC 抽象） | Phase 38 登记 |
| TD-073 | 对象存储为进程内模拟，未接入真实 S3 API | Phase 40 登记 |
| TD-074 | Spot 竞价未接真实市场做市 | Phase 40 登记 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v2.3 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 真实外部系统接入必须可配置降级（模拟实现保留为 fallback）；
- 密钥轮换必须原子且不中断验证；
- leveled LSM 演进必须零回退（保持既有 SSTable 兼容）；
- PD 等价调度只做调度策略生产化，禁止放宽一致性约束；
- 跨地域/容器/磁盘门禁：可执行项全绿 + 未执行项精确登记。

## 3. Phase 41 Goal

目标：**Real Integration Convergence & Production Hardening**，完成
8 个 Goal：

1. 真实执行门禁 Linux Runner 收敛 v7
2. 真实 S3 API 接入（TD-073 关闭方向）
3. Spot 市场真实数据源接入（TD-074 关闭方向）
4. 签名密钥轮换（TD-068 关闭方向）
5. 物化视图对象存储生命周期联动
6. 生产级 LSM 演进（leveled compaction + immutable MemTable 轮转）
7. PD 等价调度生产化（placement + 均衡 + 限流）
8. v2.4 冻结与发布流水线

## 4. Goals

### Goal 1 — 真实执行门禁 Linux Runner 收敛 v7

目标：执行或如实登记遗留门禁。

交付：

- Linux Runner 执行：TD-048、TD-049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072；
- 门禁收敛表 v7：每项状态 / 阻塞原因 / 预期消除阶段；
- 验收：可执行项全绿 + 未执行项精确登记，禁止伪报完成。

ADR：`ADR-0199 Real Runner Gate Convergence v7`。

### Goal 2 — 真实 S3 API 接入

目标：对象存储归档从模拟升级为真实 S3 兼容 API。

交付：

- `datamesh/S3ObjectStorage`：S3 兼容客户端抽象（bucket/key/put/get/
   delete + 模拟 fallback）；
- 与 ObjectStorageArchive 联动（可配置降级）；
- 验收：S3 语义矩阵 + fallback 切换 + 主权约束保留。

ADR：`ADR-0200 Real S3 Object Storage Integration`。

### Goal 3 — Spot 市场真实数据源接入

目标：spot 从模拟市场升级为真实数据源接入（抽象 + fallback）。

交付：

- `observability/cost/SpotMarketDataSource`：市场数据源抽象（真实 API
    + 模拟 fallback）；
- 与 SpotMarketFeed / SpotBidEngine 联动；
- 验收：数据源切换矩阵 + fallback + 限流/超时。

ADR：`ADR-0201 Real Spot Market Data Integration`。

### Goal 4 — 签名密钥轮换

目标：签名密钥原子轮换，不中断验证（TD-068 关闭）。

交付：

- `compliance/KeyRotationManager`：双密钥（active/next）→ 原子切换
   + 旧密钥宽限期；
- 与 SignedAttestation / SignatureVerifier 联动；
- 验收：轮换矩阵 + 宽限期验证 + 回滚。

ADR：`ADR-0202 Signing Key Rotation`。

### Goal 5 — 对象存储生命周期联动

目标：物化视图生命周期与对象存储生命周期联动（TTL → 过期删除）。

交付：

- `datamesh/ObjectLifecycleManager`：视图 TTL → 对象存储过期策略
   （模拟 S3 生命周期规则）；
- 与 ObjectStorageArchive / MaterializedViewLifecycle 联动；
- 验收：TTL → 规则生成 + 过期清理 + 恢复保护。

ADR：`ADR-0203 Object Storage Lifecycle Integration`。

### Goal 6 — 生产级 LSM 演进

目标：size-tiered 升级为 leveled compaction + immutable MemTable 轮转。

交付：

- `storage/compaction/LeveledCompactionPlanner`：L0→L1→L2 层级计划
   （大小/层数阈值）；
- `storage/memory/ImmutableMemTableRotator`：Active → Immutable →
   Flush 轮转；
- 与 Compaction / FlushScheduler 联动（零回退，保持格式兼容）；
- 验收：层级计划矩阵 + 轮转矩阵 + 兼容性。

ADR：`ADR-0204 Production LSM Evolution`。

### Goal 7 — PD 等价调度生产化

目标：placement/均衡/限流调度器生产化（PD 等价雏形）。

交付：

- `cluster/scheduler/PlacementScheduler`：放置约束（机架/可用区）；
- `cluster/scheduler/RebalanceScheduler`：负载均衡计划（epoch 保护）；
- `cluster/scheduler/QuotaScheduler`：调度配额/限流；
- 与 PlacementManager / BalanceScheduler 联动；
- 验收：约束矩阵 + 均衡矩阵 + 限流矩阵。

ADR：`ADR-0205 PD-Equivalent Scheduling & v2.4 Freeze`。

### Goal 8 — v2.4 冻结与发布流水线

目标：v2.4.0 发布候选。

交付：

- `release.yml` 扩展 v2.4.0 标签 + Phase41BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v2.4.0-release-notes.md`。

ADR：`ADR-0205`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0199 | Real Runner Gate Convergence v7 |
| ADR-0200 | Real S3 Object Storage Integration |
| ADR-0201 | Real Spot Market Data Integration |
| ADR-0202 | Signing Key Rotation |
| ADR-0203 | Object Storage Lifecycle Integration |
| ADR-0204 | Production LSM Evolution |
| ADR-0205 | PD-Equivalent Scheduling & v2.4 Freeze |

## 6. Test Plan

新增目标：**>=490 tests**（Phase 41，surefire 口径）；

Phase 1-41 全量目标：**>=7850 tests**（当前 7360）。

| Module | Count |
| --- | ---: |
| 门禁收敛 v7（JVM 级扩展） | 40 |
| 真实 S3 接入 | 65 |
| Spot 真实数据源 | 60 |
| 密钥轮换 | 60 |
| 对象生命周期联动 | 60 |
| Leveled LSM + Immutable 轮转 | 70 |
| PD 等价调度 | 75 |
| v2.4 发布/门禁 | 40 |

## 7. Documentation Deliverables

```text
docs/review/phase41-real-integration-review.md
docs/deployment/gate-convergence-v7.md
docs/datamesh/s3-integration.md
docs/observability/spot-market-real-data.md
docs/security/key-rotation.md
docs/datamesh/object-lifecycle.md
docs/storage/leveled-lsm.md
docs/cluster/pd-equivalent-scheduling.md
docs/benchmark/phase41-production-report.md
docs/release/v2.4.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v2.3 冻结协议不变；新能力 additive；
- 真实外部系统接入必须可配置降级（模拟 fallback 保留）；
- 密钥轮换必须原子、可回滚、不中断验证；
- leveled LSM 必须零回退（SSTable 格式兼容）；
- PD 等价调度只做策略生产化，禁止放宽一致性约束；
- 对象生命周期联动必须恢复保护（误删可恢复）；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase41-real-integration-production-hardening`

Commits：

```text
docs: ADR-0199~0205
feat(gates): real runner convergence v7 jvm extensions
feat(datamesh): real s3 object storage integration
feat(observability): real spot market data source
feat(compliance): signing key rotation
feat(datamesh): object storage lifecycle integration
feat(storage): leveled lsm and immutable memtable rotation
feat(cluster): pd equivalent scheduling
feat(ci): v2.4 release and gate convergence v7
docs: phase41 release
```

Merge：`merge: integrate Phase41 real integration convergence and production hardening`

Checkpoint：`checkpoint-before-phase41` / `checkpoint-after-phase41`

## 10. Success Criteria

全部满足：

```text
✅ 真实执行门禁收敛表 v7（可执行项全绿，未执行项精确登记）
✅ 真实 S3 API 接入（put/get/delete + fallback + 主权）
✅ Spot 市场真实数据源（数据源切换 + fallback + 限流）
✅ 签名密钥轮换（双密钥原子切换 + 宽限期 + 回滚，TD-068 关闭）
✅ 对象存储生命周期联动（TTL → 过期规则 + 恢复保护）
✅ 生产级 LSM（leveled 计划 + Immutable 轮转 + 零回退）
✅ PD 等价调度（placement + 均衡 + 限流矩阵）
✅ v2.4.0 发布候选（release.yml 执行/就绪）
✅ 全量回归 >=7850，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 42+，不在本阶段范围）

- 真实 Runner 全量门禁闭环（3 连绿 + 发布记录）
- leveled compaction 生产调优（写放大/读放大权衡）
- PD 调度器全自动（与自治闭环联动）
- 悲观事务 / async commit / resolved-ts（事务深度）
- Coprocessor SQL 下推（算子下推存储层）
- 全球多活自动拓扑自发现
