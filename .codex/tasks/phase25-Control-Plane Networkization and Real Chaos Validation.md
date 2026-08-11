# Phase 25 Task Prompt — Control-Plane Networkization & Real Chaos Validation

## 1. Context

当前系统已完成：

```text
Phase 1-10 : Storage(LSM/WAL/SSTable) + 冷热分层 + 并发 + mmap IO
Phase 11-18: Raft + Multi-Raft + Region + Migration + Redis Cluster Gateway
Phase 19-23: MVCC + Percolator 2PC + 事务 RPC + 生命周期 + LockResolver + 运行时
Phase 24   : 元数据 Multi-Raft(进程内) + K8s 清单 + 备份恢复 + 滚动升级 + CI E2E 交付物
```

当前基线：

```text
develop   : c520b0e（Phase 24 云原生生产发布 + 13 领域评审合并）
定位      : Cloud Native Distributed Transaction KV v1.0 RC
Tests     : 2238/2238 PASS
Benchmark : 事务 SET 144-175K ops/s
            Cross Region 2PC 45-83K txn/s
            Leader failover 164-303ms
            Transaction recovery ≈3ms
```

Phase 24 已把三个 GA 门槛的交付物全部就绪（工作流、清单、JVM 等价验证、
元数据 Raft 架构），但真实执行与最后一个网络化闭环仍未完成，即 Phase 25
的全部工作。

## 2. Remaining Technical Debt

| 编号 | 当前状态 | 目标 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E 工作流已提交、JVM 等价已验证，真实 Linux Runner 未执行 | GitHub Actions ubuntu-latest：compose up → 全链路事务测试 + 容器故障注入 |
| TD-049 | 磁盘混沌仅 JVM 语义注入（40 项），真实块设备未注入 | Linux VM：loop device + dmsetup + fio + mount remount,ro |
| TD-050 | 元数据 Multi-Raft 为进程内 Raft 组（LocalRaftTransport） | Coordinator → Netty RPC → Metadata Node1/2/3，持久化日志 + 快照 |

衍生验证项（随 K8s 集群内演练一并关闭）：

| 项 | 说明 |
| --- | --- |
| K8s 真实集群拉起 | kind/k3s：清单结构已测，运行时（探针/PVC/DNS/PDB）未验证 |
| 滚动升级真实演练 | Pod 替换 + PDB 驱逐 + 升级中写入不丢失 |
| 备份恢复真实演练 | PVC 重建后 restore → 事务可读 |

## 3. Phase 25 Goal

目标：**v1.0 GA 门槛闭环**，完成 4 个 Goal：

1. Metadata Multi-Raft 网络化（关闭 TD-050，最后一个控制面网络化闭环）
2. CI 容器 E2E 真实执行（关闭 TD-048）
3. 真实块设备磁盘混沌（关闭 TD-049）
4. Kubernetes 集群内验证与运维演练

原则（禁止变更）：

- 不修改 Raft 共识算法与事务状态机语义；
- 不修改 MVCC 语义与 2PC 协议；
- 元数据决策必须先经 Raft 持久化再执行 participant 提交；
- 所有分布式调用幂等；所有基准如实记录范围与分位；
- 环境受限项继续登记 TD，不虚报执行。

## 4. Goals

### Goal 1 — Metadata Multi-Raft 网络化（关闭 TD-050）

架构：

```text
Coordinator → TxnMetadataClient → Metadata Raft Group（Netty RPC）
                                  ├─ meta-1
                                  ├─ meta-2
                                  └─ meta-3
                                  （FileRaftLog + RaftPersistentState + Snapshot）
```

实现要求：

- `TxnMetadataNode` 接入 `MultiRaftEndpoint` 共享传输（复用 ADR-0058 多组
  单端口传输与组隔离），替换进程内 LocalRaftTransport；
- Raft 日志/状态/快照落盘（复用 ADR-0039/0040 的 FileRaftLog 与
  SnapshotManager），节点重启保留 term/votedFor/日志/快照；
- 决策路径保持 Raft-first + decisionIndex（ADR-0087/0095），禁止
  local-first apply；
- 网络故障语义：leader failover、分区下决策不丢失、提案超时重试与
  客户端重定向；
- 元数据命令继续复用 `TxnMetaCommand`（REGISTER/PREPARE/COMMIT/ROLLBACK/
  LIFECYCLE），仅传输层升级。

ADR：`ADR-0099 Metadata Multi-Raft Network Transport`。

### Goal 2 — CI Container E2E（关闭 TD-048）

Pipeline（`.github/workflows/transaction-e2e.yml` 已就绪，补真实执行）：

```text
checkout → build image → compose up --wait → health check
→ transaction suite（容器内）→ 容器故障注入 → collect logs → cleanup
```

故障注入：

| Case | 注入 | 验证 |
| --- | --- | --- |
| kill coordinator | docker kill coordinator | 重启后 recover，无提交丢失 |
| kill participant | docker kill participant-a | 重启注册，提交恢复正常 |
| kill metadata leader | docker kill meta | 新 leader 承接提案 |
| network partition | tc netem 分区 | 无 split brain、无重复提交 |

验收：同一 Runner 连续 3 次全绿。

### Goal 3 — Real Block Device Disk Chaos（关闭 TD-049）

环境：Linux Runner/VM，工具 `fio` / `dmsetup` / `fallocate` / `mount`。

| Case | 注入 | 验证 |
| --- | --- | --- |
| Disk Full | 元数据/数据盘写满后 COMMIT → restart | Raft recovery，无已提交事务丢失 |
| Readonly | `mount -o remount,ro` | commit rejected，rollback safe |
| Slow IO | `fio` 延迟注入 | no split brain、no duplicate commit |

新增：`RealBlockDeviceChaosTest`（Runner 标记 `@Tag("container")`，本地跳过）。

### Goal 4 — Kubernetes In-Cluster Validation

环境：kind（或 k3s）。

- 应用 `deploy/kubernetes/tiering-kv/`，验证 StatefulSet 3/3 就绪、
  Headless DNS 发现、PVC 绑定；
- gateway SET/GET 冒烟；metadata leader failover 演练；
- PDB 驱逐演练（drain 一个节点，quorum 保持）；
- 滚动升级演练：逐 Pod 替换 + 升级中写入不丢失；
- 备份恢复演练：PVC 重建 → restore → 事务可读。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0099 | Metadata Multi-Raft Network Transport |
| ADR-0100 | CI Container Chaos Pipeline |
| ADR-0101 | Block Device Disk Chaos |
| ADR-0102 | Kubernetes In-Cluster Validation |

## 6. Test Plan

新增目标：**>=200 tests**（Phase 25）；

Phase 1-25 全量目标：**>=2400 tests**（当前 2238）。

| Module | Count |
| --- | ---: |
| Metadata Network Raft | 60 |
| Container E2E（含参数化） | 40 |
| Real Disk Chaos（JVM 等价 + Runner） | 40 |
| K8s Manifest/Cluster | 20 |
| Rolling Upgrade / Backup 演练 | 20 |
| Final Benchmark | 10 |

## 7. Documentation Deliverables

```text
docs/review/phase25-control-plane-ga-review.md
docs/testing/phase25-container-chaos-report.md
docs/testing/phase25-block-device-chaos-report.md
docs/benchmark/phase25-final-ga-report.md
docs/deployment/kubernetes-in-cluster-guide.md
docs/release/v1.0.0-release-notes.md（GA）
```

## 8. Engineering Rules

- 所有分布式调用幂等；事务决策必须先经 Raft 持久化再执行；
- 容器/Runner 测试用 tag 隔离，本地 JVM 全量保持可复现；
- Benchmark 如实记录（范围/分位/环境/是否容器），不隐藏失败项；
- 环境受限无法执行的项登记 TD 并给出交付物，不虚报；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase25-control-plane-networkization`

Commits：

```text
docs: ADR-0099~0102
feat(meta): networkized metadata multi-raft
feat(ci): container chaos execution
feat(chaos): block device disk chaos
test(phase25): matrix suites
docs: GA release
```

Merge：`merge: integrate Phase25 control-plane GA closure`

Checkpoint：`checkpoint-before-phase25` / `checkpoint-after-phase25`

## 10. Success Criteria

全部满足：

```text
✅ TD-050 关闭（元数据 Multi-Raft 网络化 + 持久化 + 快照）——已关闭
✅ TD-048 关闭（CI 容器 E2E 真实执行 3 连绿）——交付物完成，Runner 待触发
✅ TD-049 关闭（真实块设备磁盘混沌三场景）——交付物完成，Runner 待触发
✅ K8s 集群内验证（拉起/探针/PDB/滚动升级/备份恢复演练）——脚本+门控测试就绪
✅ Tests >=2400 ——2408/2408 PASS
✅ develop merge success
✅ v1.0 GA 发布说明
```
