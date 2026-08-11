# Phase 24 Task Prompt — Cloud Native Production Release

## 1. Context

当前系统已完成：

```text
Phase 1-18 : Storage(LSM/WAL/SSTable) + Raft + Multi-Raft + Region + Gateway
Phase 19-23: MVCC + Percolator 2PC + Distributed Transaction RPC
             + Transaction Runtime + Lifecycle Persistence + LockResolver
             + Production Chaos
```

当前基线：

```text
develop  : 3684f4c merge: integrate Phase23 transaction runtime finalization
Tests    : 2007/2007 PASS
Benchmark: Gateway Transaction SET >100K ops/s
           Cross Region 2PC 33K~60K txn/s
           Recovery 0~15ms
```

## 2. Remaining Technical Debt

| 编号 | 当前状态 | 目标 |
| --- | --- | --- |
| TD-047 | 事务决策仅单节点 `TxnMetadataService`，无独立 Metadata Raft 集群 | `TxnMetadataRaftGroup`（node1/node2/node3）承载决策 |
| TD-048 | `docker-compose.transaction.yml` 可启动，但 CI 运行时 E2E 未执行 | GitHub Actions / Docker Runner：compose up → 全链路事务测试 |
| TD-049 | 磁盘混沌仅 JVM 语义注入 | 真实容器注入：`dmsetup` / `fio` / `fallocate` |

## 3. Phase 24 Goal

目标：**Cloud Native Production Release**，完成 8 个 Goal：

1. Metadata Multi-Raft 化
2. CI Docker E2E
3. Real Disk Chaos
4. Runtime Health & Graceful Shutdown
5. Backup / Restore
6. Online Upgrade
7. Kubernetes-ready Runtime
8. Final SLA Benchmark

原则（禁止变更）：

- 不修改 MVCC 语义；
- 不修改事务状态机；
- 不修改 Raft Log replication；
- 不引入新协议；
- 只做生产发布工程。

## 4. Goals

### Goal 1 — Transaction Metadata Multi-Raft（关闭 TD-047）

架构：

```text
Coordinator → TxnMetadataClient → TxnMetadataRaftGroup
                                  ├─ meta-1
                                  ├─ meta-2
                                  └─ meta-3
```

新增 `txn/meta/`：

- `TxnMetadataNode`
- `TxnMetadataRaftGroup`
- `TxnMetadataClient`
- `MetadataSnapshotManager`

复用：`MultiRaftNode` / `RaftTransport` / `Snapshot` / `Recovery`。

Metadata 命令（全部经 Raft propose → commitIndex → apply state，禁止 local-first apply）：

```text
REGISTER_TXN / PREWRITE / COMMIT / ROLLBACK / LIFECYCLE_UPDATE
```

ADR：`ADR-0095 Transaction Metadata Multi-Raft Architecture`。

### Goal 2 — CI Container Runtime E2E（关闭 TD-048）

新增 `.github/workflows/transaction-e2e.yml`，Pipeline：

```text
checkout → build image → docker compose up → health check
→ run transaction suite → collect logs → cleanup
```

环境：`ubuntu-latest` + Docker Engine + `NET_ADMIN` capability。

新增 `CiTransactionE2ETest`：

- 正常路径：SET / GET / MSET / Cross Region Txn；
- 故障路径：kill coordinator / kill participant / kill metadata leader / network partition。

验收：容器运行时 100% 可复现。

### Goal 3 — Real Disk Chaos（关闭 TD-049）

环境：Linux Docker Runner；工具：`fio` / `dmsetup` / `fallocate` / `mount`。

| Case | 注入 | 验证 |
| --- | --- | --- |
| Disk Full | Metadata leader 数据盘写满后 COMMIT → restart | Raft recovery，No lost committed transaction |
| Readonly | `mount -o remount,ro` | commit rejected，rollback safe |
| Slow IO | `fio` latency injection | no split brain，no duplicate commit |

新增：`RealDiskChaosTest`。

### Goal 4 — Runtime Health & Graceful Shutdown

Health 端点：`/health`、`/readiness`、`/liveness`，返回 raft state / leader / term / pending txn / lock count。

Graceful Shutdown 流程：

```text
SIGTERM → stop accept → finish inflight txn → flush raft → close storage
```

新增：`GracefulShutdownTest`；ADR：`ADR-0096 Production Runtime Lifecycle`。

### Goal 5 — Backup / Restore

新增 `backup/`：`BackupManager`、`RestoreManager`，覆盖：

- Metadata：TxnMetadata snapshot；
- Storage：MVCC index、SSTable、Raft snapshot。

验证闭环：write data → backup → destroy node → restore → transaction readable。

ADR：`ADR-0097 Backup Restore Strategy`。

### Goal 6 — Online Upgrade

滚动升级 v1 → v2：无停止服务、Raft quorum 保持、transaction 不丢失。

```text
node1 upgrade → wait catchup → node2 → node3
```

新增：`RollingUpgradeTest`；ADR：`ADR-0098 Online Upgrade Strategy`。

### Goal 7 — Kubernetes Ready

新增 `deploy/kubernetes/tiering-kv/`：

```text
StatefulSet.yaml / Service.yaml / ConfigMap.yaml / Secret.yaml
/ PodDisruptionBudget.yaml
```

支持：3 metadata replicas、N storage replicas、gateway deployment。

### Goal 8 — Final Production Benchmark

生成 `docs/benchmark/phase24-final-production-report.md`。

| 指标 | 目标 |
| --- | --- |
| Gateway SET（transaction） | >100K ops/s |
| Cross Region Txn | >50K txn/s |
| Leader failover | <500ms |
| Transaction recovery | <1s |

Chaos 覆盖：node kill / network partition / disk full / slow disk / rolling upgrade。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0095 | Transaction Metadata Multi-Raft |
| ADR-0096 | Production Runtime Lifecycle |
| ADR-0097 | Backup Restore Strategy |
| ADR-0098 | Online Upgrade Strategy |

## 6. Test Plan

新增目标：**>=200 tests**（Phase 24）；

Phase 1-24 全量目标：**>=2200 tests**（当前 2007）。

| Module | Count |
| --- | ---: |
| Metadata MultiRaft | 50 |
| CI Runtime | 30 |
| Disk Chaos | 40 |
| Health/Shutdown | 20 |
| Backup Restore | 30 |
| Upgrade | 20 |
| Kubernetes | 10 |
| Benchmark | 5 |

## 7. Documentation Deliverables

```text
docs/review/phase24-cloud-native-release-review.md
docs/testing/phase24-chaos-report.md
docs/benchmark/phase24-final-production-report.md
docs/deployment/kubernetes-production-guide.md
docs/release/v1.0-release-notes.md
```

## 8. Engineering Rules

- 不修改 Raft 共识算法与事务状态机语义；
- 所有分布式调用幂等；
- 事务决策必须先经 Raft 持久化再执行 participant 提交；
- 所有 Benchmark 如实记录（范围/分位/环境），不隐藏失败项；
- 所有缺陷登记 TD；
- 使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase24-cloud-native-release`

Commits：

```text
docs: ADR-0095~0098
feat: metadata multiraft
feat: ci runtime
feat: disk chaos
feat: backup restore
feat: rolling upgrade
docs: production release
```

Merge：`merge: integrate Phase24 cloud native release`

Checkpoint：`checkpoint-before-phase24` / `checkpoint-after-phase24`

## 10. Success Criteria

全部满足：

```text
✅ Txn Metadata Multi-Raft
✅ CI Docker E2E
✅ Real Disk Chaos
✅ Health Check
✅ Graceful Shutdown
✅ Backup Restore
✅ Rolling Upgrade
✅ Kubernetes manifests
✅ Benchmark complete
✅ Chaos complete
✅ Tests >=2200
✅ develop merge success
```
