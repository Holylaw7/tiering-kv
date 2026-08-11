# Phase 24 评审报告：Cloud Native Production Release

Phase 24 · 2026-08-11

## 1. 结论

Phase 24 完成云原生生产发布闭环：

- 事务元数据 Multi-Raft 化（ADR-0095，关闭 TD-047 的主体）；
- CI 容器 E2E 管道（`.github/workflows/transaction-e2e.yml`，关闭 TD-048
  的交付物主体，真实 Runner 执行待 CI 触发）；
- 真实磁盘混沌矩阵（ADR-0094 延续，JVM 等价注入，TD-049 交付物主体）；
- 运行时健康探针与优雅停机（ADR-0096）；
- 备份 / 恢复闭环（ADR-0097）；
- 滚动升级协调器（ADR-0098）；
- Kubernetes 生产清单（StatefulSet / Service / ConfigMap / Secret / PDB /
  Gateway）；
- 最终 SLA 基准（docs/benchmark/phase24-final-production-report.md）。

新增 **231 项测试**，全量回归 **2238/2238 全绿**（Phase 1–24）。

## 2. ADR

| ADR | 主题 |
| --- | --- |
| 0095 | Transaction Metadata Multi-Raft |
| 0096 | Production Runtime Lifecycle |
| 0097 | Backup Restore Strategy |
| 0098 | Online Upgrade Strategy |

## 3. 关键决策与修复

1. **元数据命令 Raft-first**：`TxnMetadataNode`（RaftNode + 元数据状态机）
   承接 REGISTER/PREPARE/COMMIT/ROLLBACK/LIFECYCLE，决策经
   `withDecisionIndex` 在 apply 阶段落状态，禁止 local-first apply。
2. **快照全状态保持**：快照不仅保存事务条目，还保存生命周期记录；条目状态
   （REGISTERED/PREPARED/COMMITTED/ROLLED_BACK）以 UTF 直存，不再按
   `TxnLifecycleState` ordinal 编码（修复 `REGISTERED` 无枚举崩溃）。
3. **并发快照一致性**：count 与数据取自同一份不可变副本，修复并发写入下
   快照尾损坏（EOF 逃逸为 `IllegalStateException`）。
4. **TxnRpcCodec 64KB 上限**：byte[] 长度前缀由 `writeShort` 升级为
   `writeInt`，修复 1MB 值被静默截断为空的真实缺陷（大 value 往返测试覆盖）。
5. **零超时语义**：GracefulShutdown / UpgradeCoordinator 先做一次即时检查，
   使 drain/catchup 已满足时零超时也能正确完成。
6. **E2E 夹具抽取**：TCP 全链路夹具提取为同包顶层
   `Phase24E2EFixture`，两套 E2E 套件复用，消除嵌套 record 可见性耦合。

## 4. 测试与混沌覆盖

| 模块 | 用例 | 覆盖 |
| --- | ---: | --- |
| Metadata Multi-Raft | 55 | 选举 / 提案 / 故障转移 / 快照状态矩阵 / 损坏容忍 |
| CI Runtime E2E | 31 | SET/GET/跨区/MSET/回滚/kill coordinator/kill participant/分区 |
| Disk Chaos | 40 | disk full / readonly / slow / 多故障恢复 / 回滚安全 |
| Health & Shutdown | 22 | readiness/liveness/JSON/drain/超时/中断/closer 隔离 |
| Backup / Restore | 34 | 全状态矩阵 / tombstone / 多版本 / 缺文件 / 损坏 |
| Rolling Upgrade | 23 | quorum 保持 / 丢失中止 / 中断 / 异常传播 / 零超时 |
| Kubernetes 清单 | 10 | 结构 / 副本 / 端口 / PDB / Secret / ConfigMap |
| Final Benchmark | 5 | SET / 多区事务 / 锁解析 / 恢复 / leader failover |

## 5. 基准（进程内 JVM 等价，Windows localhost）

| 指标 | 目标 | 实测 |
| --- | ---: | --- |
| Gateway SET（transaction） | >100K ops/s | 144–175K ✅ |
| Cross Region Txn | >50K txn/s | 45–83K（峰值达标，均值如实记录） |
| Leader failover | <500ms | 164–303ms ✅ |
| Transaction recovery | <1s | ≈3ms ✅ |
| Lock resolve（500 锁） | — | 19–36ms |

## 6. 局限（不隐藏）

1. 元数据 Multi-Raft 为进程内 Raft 组（LocalRaftTransport），三节点网络化
   传输仍待跨机验证（登记 TD-050）；
2. 真实 Docker 磁盘混沌（dmsetup/fio/fallocate）与 CI 容器 E2E 需
   Linux + Docker Runner 执行，交付物（工作流、compose、清单）已就绪
   （TD-048/TD-049 登记为「交付物完成、执行待 Runner」）；
3. 基准为 JVM 进程内 + localhost，未包含跨机网络与真实磁盘冷启动；
4. 滚动升级的「升级中写入不丢失」由 JVM 等价验证覆盖，容器级验证待 CI。

## 7. 下一步

- 在 GitHub Actions Linux Runner 上触发 transaction-e2e 工作流；
- 元数据 Multi-Raft 网络化（Netty RPC 传输 + 持久化 Raft 日志）；
- K8s 清单的集群内 e2e（kind/k3s）与备份恢复演练。
