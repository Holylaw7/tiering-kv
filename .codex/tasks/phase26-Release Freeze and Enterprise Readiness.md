# Phase 26 Task Prompt — Release Freeze & Enterprise Readiness

## 1. Context

当前系统已完成：

```text
Phase 1-18 : Storage / Raft / Multi-Raft / Region / Migration / Gateway
Phase 19-23: MVCC / Percolator 2PC / 事务 RPC / 生命周期 / LockResolver / 运行时
Phase 24   : 元数据 Multi-Raft(进程内) + K8s 清单 + 备份恢复 + 滚动升级 + CI E2E
Phase 25   : 元数据 Multi-Raft 网络化（TD-050 关闭）+ 容器/块设备混沌 + kind 验证交付物
```

当前基线：

```text
develop   : 47f5477 merge: integrate Phase25 control-plane GA closure
定位      : Production-grade Distributed Transaction KV（v1.0.0 GA 候选）
Tests     : 2408/2408 PASS（另 6 项容器门控本地跳过）
Benchmark : 事务 SET 144-175K ops/s；跨区 2PC 45-83K txn/s
            元数据 failover 110-160ms；恢复 ≈3ms
```

Phase 25 已把最后一个控制面网络化闭环（TD-050）关闭。Phase 26 不再堆
功能，转入 **Release Engineering + Enterprise Capability**：冻结接口、
完成真实环境验证、补齐企业级能力，收敛为 v1.0.0 发布候选。

## 2. Release 前置项（Phase 25 遗留，必须先于发布冻结）

| 编号    | 内容                                                          | 状态               |
| ------- | ------------------------------------------------------------- | ------------------ |
| TD-048  | CI 容器 E2E + 容器故障注入真实 Runner 执行（3 连绿）          | 交付物就绪，待执行 |
| TD-049  | 真实块设备磁盘混沌（loop/dmsetup/fio/remount）                | 交付物就绪，待执行 |
| K8S-001 | kind 集群内验证（StatefulSet/PDB 驱逐/网关冒烟/备份恢复演练） | 脚本就绪，待执行   |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- 不引入破坏性协议变化；所有新增能力 additive；
- 冻结后的接口变更必须走 ADR 兼容性评审。

## 3. Phase 26 Goal

目标：**v1.0 Release Freeze & Enterprise Readiness**，完成 8 个 Goal：

1. v1.0 API / Protocol Freeze
2. Production Benchmark Matrix
3. PITR Backup
4. CDC Streaming
5. Security GA
6. Kubernetes Operator
7. Production CLI（tierctl）
8. Release Automation

完成后定位升级：`Production-grade Distributed Transaction KV`
→ `Enterprise-ready Distributed Database v1.0`。

## 4. Goals

### Goal 1 — v1.0 API & Protocol Freeze

冻结范围：Client API / RESP Protocol / RPC Protocol / Metadata Command /
Storage Format。

协议版本：

```text
RPC_VERSION=1
RESP_VERSION=2
STORAGE_FORMAT_VERSION=1
```

交付：

- `docs/api/compatibility-guide.md`、`docs/api/protocol-version.md`、
  `docs/api/upgrade-policy.md`；
- `ProtocolCompatibilityTest`：旧客户端 connect / read / write /
  transaction 持续可用；冻结后协议变更需 ADR 兼容性评审；
- 网关 `CLUSTER` 与 `AUTH` 行为写入兼容性清单（如实标注子集）。

ADR：`ADR-0103 Protocol Compatibility Policy`。

### Goal 2 — Production Benchmark Matrix

环境：Linux + Docker（Kubernetes 可选）；拓扑：

```text
Gateway ×3
Metadata Raft ×3
Storage Node ×6
Region ×N
```

验收指标：

| 指标            |        目标 |
| --------------- | ----------: |
| GET             |   >1M ops/s |
| SET             | >200K ops/s |
| Transaction     |  >50K txn/s |
| GET P99         |        <5ms |
| SET P99         |       <20ms |
| TXN P99         |      <100ms |
| Leader failover |      <500ms |
| Node restart    |         <5s |

输出：`docs/benchmark/v1-final-production-report.md`（如实记录环境/分位/
是否容器；跨机与 JVM 口径分离）。

### Goal 3 — PITR Backup

从 snapshot backup 升级为 Point In Time Recovery：

```text
T0 snapshot → T1 WAL archive → T2 restore timestamp
```

交付：`backup/pitr/`（WALArchiveManager / CheckpointManager /
RestoreTimeline）。

验证闭环：write → snapshot → write more → crash → restore T1 →
verify。

ADR：`ADR-0104 Point In Time Recovery`。

### Goal 4 — CDC Streaming

架构：

```text
Raft Apply → CDC Log → Stream Consumer
```

交付：`cdc/`（ChangeEvent / CDCProducer / CDCConsumer /
CDCCheckpoint），事件类型 PUT / DELETE / TXN_COMMIT / REGION_MOVE，
exactly-once checkpoint 语义。

测试：`CDCRecoveryTest`（消费者崩溃恢复、重放幂等、checkpoint 前进）。

ADR：`ADR-0105 CDC Architecture`。

### Goal 5 — Security GA

基于 Phase 25 Auth/TLS foundation 升级：

- Authentication：RBAC + Token + Rotation；交付 Role / Permission /
  CredentialManager；权限域 READ / WRITE / ADMIN / BACKUP / CDC；
- TLS：mTLS + RPC encryption + certificate rotation（延续 ADR-0055）；
- 测试：`SecurityChaosTest`（token 过期/轮换/越权/证书失效）。

ADR：`ADR-0106 Enterprise Security Model`。

### Goal 6 — Kubernetes Operator

从 yaml deployment 升级为 operator：

```text
deploy/operator/
├─ TieringKVCluster CRD（tieringkv.io/v1）
├─ Controller
└─ Reconciler
```

自动能力：create cluster / scale node / replace failed node /
rolling upgrade / backup trigger。

ADR：`ADR-0107 Kubernetes Operator Design`。

### Goal 7 — Production CLI（tierctl）

```bash
tierctl cluster status
tierctl region list
tierctl txn inspect
tierctl backup create
tierctl restore
tierctl chaos run
tierctl upgrade
```

CLI 复用现有 runtime/metrics 数据源，不引入第二套状态视图。

### Goal 8 — Release Automation

新增 `.github/workflows/release.yml`：

```text
test → benchmark → security scan → docker build → publish image
→ generate release notes
```

版本序列：`v1.0.0-rc1` → `v1.0.0`；镜像 tag 与 release notes 自动生成。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR      | 主题                          |
| -------- | ----------------------------- |
| ADR-0103 | Protocol Compatibility Policy |
| ADR-0104 | Point In Time Recovery        |
| ADR-0105 | CDC Architecture              |
| ADR-0106 | Enterprise Security Model     |
| ADR-0107 | Kubernetes Operator Design    |

## 6. Test Plan

新增目标：**>=300 tests**（Phase 26）；

Phase 1-26 全量目标：**>=2700 tests**（当前 2408）。

| Module                 | Count |
| ---------------------- | ----: |
| Protocol Compatibility |    40 |
| Production Benchmark   |    30 |
| PITR                   |    50 |
| CDC                    |    50 |
| Security               |    40 |
| Operator               |    40 |
| CLI                    |    20 |
| Release CI             |    30 |

## 7. Documentation Deliverables

```text
docs/release/v1.0.0-release-notes.md
docs/api/compatibility-guide.md
docs/api/protocol-version.md
docs/api/upgrade-policy.md
docs/benchmark/v1-final-production-report.md
docs/backup/pitr-guide.md
docs/cdc/cdc-design.md
docs/operator/operator-guide.md
docs/security/security-whitepaper.md
```

## 8. Engineering Rules

- 冻结接口变更必须走 ADR 兼容性评审；所有新增能力 additive；
- 基准如实记录（环境/分位/容器口径），JVM 与跨机分离；
- 容器/Runner 测试 tag 隔离，本地 JVM 全量保持可复现；
- 环境受限项登记 TD，不虚报执行；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase26-v1-release`

Commits：

```text
docs: ADR-0103~0107
feat(protocol): api freeze
feat(pitr): point in time recovery
feat(cdc): change data capture
feat(security): enterprise security ga
feat(operator): kubernetes operator
feat(cli): tierctl
feat(ci): release pipeline
docs: v1 release
```

Merge：`merge: integrate Phase26 v1 release`

Checkpoint：`checkpoint-before-phase26` / `checkpoint-after-phase26`

## 10. Success Criteria

全部满足：

```text
✅ Phase 25 遗留 Runner 执行（TD-048/049 + K8s 演练）——发布前置
✅ API / Protocol Freeze（RESP2 / RPC v1 / 存储格式 v1）
✅ Production Benchmark（Linux/Docker 拓扑，全部指标如实报告）
✅ PITR Backup（restore T1 闭环验证）
✅ CDC Streaming（exactly-once checkpoint）
✅ Enterprise Security（RBAC + mTLS + rotation）
✅ Kubernetes Operator（CRD + Controller + Reconciler）
✅ tierctl CLI
✅ Release Automation（rc1 → v1.0.0）
✅ Tests >=2700
✅ v1.0.0 release candidate 发布说明
```

## 11. 后续方向（Phase 27+，不在本阶段范围）

- Multi-Region Replication
- Geo Distributed Transaction
- SQL Layer
- Vector Index
- Enterprise SaaS Control Plane
