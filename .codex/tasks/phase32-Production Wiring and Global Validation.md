# Phase 32 Task Prompt — Production Wiring & Global Validation

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
```

当前基线：

```text
develop   : 110b762 merge: integrate Phase31 autonomous resharding and global active-active
定位      : Enterprise-ready Distributed Database（v1.4.0）
Tests     : 4000/4000 PASS（另 6 项容器门控本地跳过）
新能力    : 自动重分片、SQL 2PC 桥接、向量双写、全球多活、账单滚动、多云、控制台
```

Phase 31 完成自治与多活能力（多为原型/桥接）。Phase 32 把这些能力
**接线到生产路径**：SQL 写 2PC 真实提交、控制台 REST 服务、自动重分片
并发迁移、全球多活网关冲突审计、全局多活自动选主、跨云数据主权，
并执行跨地域真实基准与 v1.5 冻结。

## 2. Release 前置项（Phase 25–31 遗留，先于新功能执行）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入真实 Runner 执行（3 连绿） | 交付物就绪，待执行 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行 |
| REL-001 | release.yml（v1.1–v1.4）真实运行记录 | 流水线就绪，待触发 |
| BM-001 | 跨机 Production Benchmark（Gateway×3 / Metadata×3 / Storage×6） | 本地口径完成，跨机待 Runner |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间真实基准 | Phase 27–31 进程内完成，跨机待执行 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v1.4 冻结协议不变，扩展必须走 ADR-0103 兼容评审；
- 单向/双向复制、分布式 SQL/向量、重分片、Active-Active 零回退；
- 生产接线必须端到端可验证（单元 + 集成 + 跨地域）。

## 3. Phase 32 Goal

目标：**Production Wiring & Global Validation**，完成 8 个 Goal：

1. SQL 写 2PC 真实接线
2. 控制台 REST 服务
3. 自动重分片并发迁移
4. 全球多活网关冲突审计
5. 全局多活自动选主与流量调度
6. 跨云数据主权与合规
7. 跨地域真实基准与验证
8. v1.5 冻结与生产发布

## 4. Goals

### Goal 1 — SQL 写 2PC 真实接线

目标：SqlTxn2PcBridge 接入真实 Geo/分布式 2PC。

交付：

- `sql/txn/SqlTxn2PcExecutor`：BEGIN → WriteOp → GeoTransactionCoordinator
  真实提交（prewrite/commit）→ COMMIT；
- 跨 Region 写事务端到端 + 回滚幂等；
- RBAC WRITE 校验在提交前执行；
- 验收：与原生 2PC 语义等价（提交/回滚/重复提交）。

ADR：`ADR-0138 SQL Write 2PC Production Wiring`。

### Goal 2 — 控制台 REST 服务

目标：ConsoleApi 升级为 HTTP 服务。

交付：

- `console/rest/`：ConsoleRestServer（Netty/HTTP 或 JDK HttpServer）+
  路由（/tenants /metrics /alerts）+ JSON 序列化；
- 令牌头校验（RBAC，ADR-0110）；
- 自服务：租户创建集群（TenantClusterPlanner 联动）；
- 验收：HTTP 参数化矩阵 + 权限矩阵 + 并发请求。

ADR：`ADR-0139 Console REST Service`。

### Goal 3 — 自动重分片并发迁移

目标：AutoReshardController 触发 → 并发迁移执行。

交付：

- `sharding/auto/ConcurrentReshardExecutor`：多线程逐分片迁移 +
  校验 + 原子切换（复用 Phase 30 ShardRouter/ShardMigration）；
- 迁移限速 + 失败回滚 + 熔断联动；
- 验收：并发迁移吞吐、切换一致性、熔断不放大故障。

ADR：`ADR-0140 Concurrent Auto Resharding`。

### Goal 4 — 全球多活网关冲突审计

目标：网关地域亲和写 + 冲突事件审计。

交付：

- `gateway/`：RegionAffinityRouter（地域亲和写路由）；
- Active-Active 冲突 → 审计日志（ConflictAuditLog：region/key/ts/
  winner）；
- 读水位（Phase 30）在网关层执行；
- 验收：冲突审计完整、无环回、读水位正确。

ADR：`ADR-0141 Active-Active Gateway Conflict Audit`。

### Goal 5 — 全局多活自动选主与流量调度

目标：地域故障自动切换写路由。

交付：

- `replication/active/LeaderSelector`：地域健康探测 + 自动选主 +
  写路由切换；
- 降级：主地域故障 → 备地域接管（RPO 由复制模式决定）；
- 验收：故障矩阵切换正确、无脑裂（仲裁兜底）。

ADR：`ADR-0143 Global Leader Selection & Data Sovereignty`。

### Goal 6 — 跨云数据主权与合规

目标：数据驻留/合规标记。

交付：

- `compliance/`：DataResidencyPolicy（region → 驻留要求）、
  ComplianceValidator（迁移/复制前校验）；
- 禁止：跨驻留边界的复制/迁移（默认拒绝）；
- 验收：策略矩阵 + 违规拒绝。

ADR：`ADR-0143`。

### Goal 7 — 跨地域真实基准与验证

目标：全球多活/事务跨地域数据。

交付：

- 跨地域基准（Linux Runner）：SQL 2PC 延迟、Active-Active 冲突率/
  收敛时间、自动选主 RTO、读陈旧度（如实记录）；
- 旧客户端兼容矩阵继续执行（ADR-0103）；
- `docs/benchmark/phase32-production-report.md`。

ADR：`ADR-0142 Cross-Region Validation & v1.5 Freeze`。

### Goal 8 — v1.5 冻结与生产发布

目标：v1.5.0 发布候选。

交付：

- `release.yml` 扩展 v1.5.0 标签；
- 生产发布：镜像/清单/文档全链路；
- 验收：发布流水线真实运行 + 全量回归 ≥4200。

ADR：`ADR-0142`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0138 | SQL Write 2PC Production Wiring |
| ADR-0139 | Console REST Service |
| ADR-0140 | Concurrent Auto Resharding |
| ADR-0141 | Active-Active Gateway Conflict Audit |
| ADR-0142 | Cross-Region Validation & v1.5 Freeze |
| ADR-0143 | Global Leader Selection & Data Sovereignty |

## 6. Test Plan

新增目标：**>=250 tests**（Phase 32）；

Phase 1-32 全量目标：**>=4200 tests**（当前 4000）。

| Module | Count |
| --- | ---: |
| SQL 写 2PC 真实接线 | 45 |
| 控制台 REST | 40 |
| 并发自动重分片 | 40 |
| 网关冲突审计 | 40 |
| 自动选主/流量调度 | 30 |
| 数据主权/合规 | 25 |
| 跨地域基准/发布 | 20 |
| 混沌/边缘矩阵 | 10 |

## 7. Documentation Deliverables

```text
docs/review/phase32-production-wiring-review.md
docs/sql/write-2pc-production.md
docs/console/rest-service.md
docs/sharding/concurrent-resharding.md
docs/multi-region/gateway-conflict-audit.md
docs/multi-region/global-leader-selection.md
docs/compliance/data-sovereignty.md
docs/benchmark/phase32-production-report.md
docs/release/v1.5.0-release-notes.md
```

## 8. Engineering Rules

- v1.0–v1.4 冻结协议不变；新能力 additive；
- 生产接线必须端到端可验证（单元 + 集成 + 跨地域）；
- SQL 写必须经 2PC（禁止旁路事务状态机）；
- 自动重分片并发迁移必须可熔断可回滚；
- Active-Active 冲突必须收敛可审计；自动选主必须防脑裂；
- 数据主权违规默认拒绝；
- 容器/Runner 测试 tag 隔离；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase32-production-wiring-global-validation`

Commits：

```text
docs: ADR-0138~0143
feat(sql): production 2pc wiring
feat(console): rest service
feat(sharding): concurrent auto resharding
feat(gateway): conflict audit
feat(active): global leader selection
feat(compliance): data sovereignty
feat(ci): v1.5 release and cross-region benchmark
docs: phase32 release
```

Merge：`merge: integrate Phase32 production wiring and global validation`

Checkpoint：`checkpoint-before-phase32` / `checkpoint-after-phase32`

## 10. Success Criteria

全部满足：

```text
✅ SQL 写 2PC 真实接线（与原生 2PC 语义等价）——执行器完成，真实协调器端到端待 Phase 33
✅ 控制台 REST 服务（HTTP + RBAC + 自服务）——已完成（ADR-0139）
✅ 自动重分片并发迁移（限速 + 校验 + 回滚 + 熔断）——已完成（ADR-0140）
✅ 全球多活网关冲突审计（冲突完整审计 + 读水位）——已完成（ADR-0141）
✅ 全局多活自动选主（故障矩阵切换 + 防脑裂）——已完成（ADR-0143）
✅ 跨云数据主权（驻留策略 + 违规拒绝）——已完成（ADR-0143）
✅ 跨地域真实基准（RTT/冲突率/收敛/RTO 如实记录）——进程内口径完成，Runner 待执行
✅ v1.5.0 发布候选（release.yml 执行）——流水线扩展完成，执行待 Runner
✅ 全量回归 >=4200，复制/查询/重分片/多活路径零回退——4251/4251 PASS（新增 251）
```

## 11. 后续方向（Phase 33+，不在本阶段范围）

- 控制台多租户 SaaS 商业化（计费/市场闭环）
- AI 驱动容量规划与自动运维
- 数据网格（跨业务域联邦查询）
- 全球多活配额/优先级流量治理
- 完整企业合规（审计日志导出/法规映射）
