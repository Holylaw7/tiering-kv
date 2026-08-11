# Phase 24 评审报告：Cloud Native Production Release

Phase 24 · 2026-08-11

## 1. 结论

Phase 24 完成云原生生产发布闭环，当前定位：**Cloud Native Distributed
Transaction KV v1.0 RC**：

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

## 1.1 核心架构完成度（领域矩阵）

| 领域 | 阶段 | 完成度 | 说明 |
| --- | --- | --- | --- |
| Storage Engine | 1–10 | ✅ | MemTable / WAL / SSTable / Compaction / mmap / Memory Pool |
| Raft | 11–15 | ✅ | 选举 / 日志持久化 / 快照 / 批量流水线复制 / 安全 RPC |
| Multi-Raft | 16–18 | ✅ | Region 组、共享传输、零拷贝批量写、epoch 路由 |
| Region / Migration | 16–18 | ✅ | split/merge、slot 迁移、并行迁移、自动均衡 |
| Redis Gateway | 17–20 | ✅ | RESP2 + CLUSTER 子集 + MVCC 自动事务化 |
| MVCC / Snapshot Read | 19 | ✅ | 多版本 + HLC + Snapshot Read + GC |
| Percolator 2PC | 19–21 | ✅ | Prewrite/Commit/Rollback，TCP 化 |
| Distributed Transaction | 19–21 | ✅ | 跨区 2PC + 网络重试 + 幂等 RPC |
| Lock Resolver | 22–23 | ✅ | CHECK/RESOLVE/HEARTBEAT RPC + 状态缓存 |
| Recovery | 19–24 | ✅ | 崩溃恢复 / 事务恢复 / 备份恢复闭环 |
| Metadata Multi-Raft | 24 | ✅（架构） | TxnMetadataNode + 快照 + decisionIndex；传输进程内（TD-050） |
| Cloud Runtime | 24 | ✅ | Health / Readiness / Liveness + Graceful Shutdown |
| Kubernetes | 24 | ✅ | StatefulSet / Service / ConfigMap / Secret / PDB / Gateway |
| Backup / Restore | 24 | ✅ | 元数据快照 + MVCC 索引 destroy→restore 闭环 |
| Rolling Upgrade | 24 | ✅ | 逐节点升级 + 追平等待 + quorum 保持 |
| CI E2E | 24 | ✅（交付物） | JVM E2E 已执行；容器 Runner 待触发（TD-048） |
| Operational Lifecycle | 24 | ✅ | 探针 / 停机 / 升级 / 备份 / 发布说明 |

**结论**：数据面（存储/冷热/IO）、事务面（2PC/锁/恢复）、控制面
（Raft 决策/元数据组）与运维面（K8s/CI/生命周期）主链路均已闭环；
TD-050（元数据 Multi-Raft 网络化）是进入 v1.0 GA 前的最后一个控制面缺口。

## 2. 领域评审（13 领域）

### 2.1 LSM / Storage Engine

- 分层：WAL → MemTable（256 段 SkipList + 分段锁）→ Flush → SSTable →
  Compaction；冷读栈 mmap + BlockCache + Off-Heap MemoryPool；
- ADR：0007–0009 / 0014–0019 / 0020–0022 / 0026–0028；
- 基准：WAL append P99≈6.8μs、SSTable 峰值 104MB/s、随机 GET P99
  0.021–0.053ms、迁移 308K ops/s、MVCC GC 107–285MB/s；
- 优点：装饰器隔离（持久化/热度/调度不侵入 MemTable）、Tombstone/TTL/
  版本/CRC 全链路语义、mmap 工程化（未用 Unsafe unmap）；
- 风险：TD-009 cold-cache 基准、TD-012 leveled compaction、
  TD-013 Immutable MemTable 轮转；
- 结论：完成度最高的领域，剩余项属 GA 后性能演进。

### 2.2 Raft Consensus

- 范围：RaftNode + 选举 + 日志复制 + commit/apply；FileRaftLog（CRC32C、
  SYNC/ASYNC/NONE、尾部截断）+ PersistentState + Snapshot/InstallSnapshot；
  Netty TCP RPC + batch/流水线 + CommitNotifier + 异步提案 + TimeoutNow；
- ADR：0037–0042 / 0044 / 0046 / 0050 / 0051 / 0054；
- 基准：异步提案 129–331K ops/s、复制滞后 <1ms、交接 24ms、
  failover 124–310ms；
- 修复：matchIndex=0 错误提交、死节点反复选举、propose Future 挂起、
  截断日志虚假完成；
- 风险：无 ReadIndex/Lease 线性化读、无成员变更 API；
- 结论：协议正确性经真实缺陷修复验证，剩余缺口在元数据组网络化（TD-050）。

### 2.3 Multi-Raft

- 范围：每 Region 独立 Raft 组 + 单端口共享传输（MultiRaftEndpoint）+
  RegionEpoch 路由守卫 + Placement/Balance + UnifiedRouter；
- ADR：0057–0060 / 0065–0067；
- 基准：1/2/4 组 110/222/404K ops/s（近线性）、并行迁移 100B 209MB/s、
  网关 GET 719K / SET 590K ops/s；
- 修复：滞后副本回填缺陷（Phase 16 混沌发现）；
- 风险：TD-017 在线扩容协议、TD-050 元数据组传输进程内；
- 结论：控制面核心骨架，Region 组已 TCP 化，元数据组是最后缺口。

### 2.4 Region 生命周期

- 范围：create → split/merge（写缓冲）→ 并行迁移（ChunkCheckpoint）→
  路由切换（epoch）→ 失败回滚（子组独立日志）→ leader 交接（TimeoutNow）+
  自动均衡（BalanceScheduler）；
- ADR：0061–0067；
- 基准：并行迁移 100B 209MB/s / 1KB 986MB/s、交接 24ms、
  Multi-Raft 近线性扩展；
- 混沌：RegionChaosTest 10 项（分裂并发写 / 合并故障恢复 / 延迟丢包交接 /
  组隔离）+ ChaosClusterTest 20 项；
- 风险：TD-017 扩容协议、均衡基于 JVM 合成指标；
- 结论：生命周期状态机完整（写缓冲 + epoch + 回滚 + 断点续传）。

### 2.5 Redis Cluster Gateway

- 范围：NettyClusterGateway（真实 TCP）+ UnifiedClusterGateway +
  CLUSTER SLOTS/MOVED + AutoTransactionExecutor（MVCC 自动事务化）；
- ADR：0066 / 0068 / 0079；
- 基准：集群网关 GET 719K / SET 590K ops/s、自动事务 GET 2.0–6.9M /
  SET 141–389K、pipeline64/128 465K / 1.14M、事务网关 SET 144–175K；
- 优点：网关薄路由稳、单命令自动事务化、真实 TCP + 批量响应；
- 风险：TD-038（NODES/ASK 子集）、无 AUTH/ACL、事务路径吞吐成本；
- 结论：可用集群入口已成立，协议完整度与安全是 GA 前补强项。

### 2.6 MVCC

- 范围：MvccStorageEngine 多版本索引 + HLC + Snapshot Read + LockTable/
  ConflictDetector + Prewrite/Commit/Rollback + GC + PersistentMvccIndex；
- ADR：0071–0075 / 0077–0078 / 0080；
- 基准：批量 GC 107–285MB/s（5–10×）、单 Region 324–651K txn/s、
  跨区（存储层）62–158K txn/s、恢复 1–4ms；
- 语义：LOCK provisional 不可见、tombstone、readTS 快照读、墙上时钟锁过期；
- 风险：SI 未升级 SSI、全链路 TCP 后跨区吞吐降至 45–83K、版本索引主体在内存；
- 结论：事务面地基，TD-041 关闭，剩余为隔离级别与冷层下沉演进。

### 2.7 Distributed 2PC

- 范围：DistributedTxnRouter + RegionTxnClient/RpcTxnTransport +
  TransactionParticipant（LOCKED→PREPARED→COMMITTED/ROLLED_BACK）+
  元数据 Raft-first 决策（decisionIndex）+ 生命周期 + LockResolver；
- ADR：0073 / 0081 / 0083–0084 / 0087–0095；
- 基准：单 Region（存储层）324–651K、全链路 SET 144–175K、跨区 45–83K、
  恢复 ≈3ms、锁解析 19–36ms；
- 优点：Raft-first 决策 + 幂等 RPC（ALREADY）+ 恢复幂等，故障路径
  （kill -9/分区/磁盘写满）无丢失无重复；
- 风险：全链路吞吐为存储层 1/2–1/3、单键也走完整 2PC、TD-050 决策链路网络化；
- 结论：Percolator 语义的工程化闭环，缺陷均有 ADR 记录。

### 2.8 Transaction Recovery

- 分层：进程崩溃（TxnJournal 重放）、参与者重启（幂等 RPC）、元数据决策
  （Raft + decisionIndex + 快照）、悬挂锁（LockResolver）、TTL 超时
  （生命周期持久化）、磁盘故障（restart + recover）、节点销毁
  （Backup/Restore）、存储崩溃（WAL/RaftLog 重放）；
- ADR：0076 / 0081 / 0087–0089 / 0091 / 0094–0095 / 0097；
- 基准：恢复 1–4ms → ≈3ms、元数据重启 ≈194ms、failover 164–303ms；
- 测试：CoordinatorCrash 29、TxnNetworkFailure 52、Chaos 32、DiskChaos 56、
  LifecyclePersistence 31、Backup/Restore 34、Snapshot 41；
- 风险：跨机时钟偏差注入缺失、大事务恢复无分页重放、增量 WAL 演练未基准化；
- 结论：每类故障都有恢复机制 + 测试 + 量化基准，是"最不怕问"的领域。

### 2.9 Metadata Consensus

- 演进：单机元数据服务（Phase 11）→ MetadataRaftGroup（13）→ 持久化 +
  快照（14）→ 事务元数据 Raft + decisionIndex（19–22）→ TxnMetadataNode
  三节点组 + 快照全状态（24）；
- ADR：0036 / 0047 / 0052 / 0084 / 0087 / 0091 / 0095；
- 基准：failover 164–303ms、重启恢复 ≈194ms；
- 能力：Raft-first 决策、决策序、快照（条目 + 生命周期）全状态保持、
  截断容忍、备份恢复不降级；
- 风险：TD-050（传输进程内）、两套元数据组未统一传输框架、快照全量无增量；
- 结论：决策核心闭环，网络化是 v1.0 GA 最后一步。

### 2.10 Container Runtime

- 交付：Dockerfile（修复 netty-tcnative classifier / Main-Class / fat jar）、
  docker-compose ×3、chaos-netem.sh、四角色 TxnRuntimeMain、CI E2E 工作流；
- ADR：0069 / 0082 / 0086 / 0090 / 0093–0094 / 0096；
- 验证：镜像构建 ✅、Docker 三节点混沌（netem/分区/kill -9）✅（TD-040）、
  JVM 等价 E2E/磁盘混沌 ✅；GitHub Actions 与真实块设备注入 ⏳（TD-048/049）；
- 优点：镜像缺陷有根因记录、运行形态贴近生产、交付与执行状态分离如实登记；
- 结论：交付物完成度高于执行完成度，剩余缺口全部是"等 Linux Runner"。

### 2.11 Kubernetes Deployment

- 清单：StatefulSet（meta/storage ×3 + PVC）、Service（Headless + ClusterIP
  :6379）、ConfigMap（start.sh + regions.conf）、Secret、PDB（minAvailable=2）、
  Gateway Deployment ×2；
- 设计：Headless 发现、PDB 保 quorum、ConfigMap 集中参数、Secret 分离、
  TCP 探针 + 优雅停机；
- 校验：KubernetesManifestTest 10 项（结构/副本/端口/PDB/Secret/REGIONS）；
- 风险：未在真实集群（kind/k3s）验证、storageClassName 未参数化、
  Secret 占位值、无 HPA/Ingress/多 AZ 约束；
- 结论：清单 + 文档完整且可结构测试，真实集群拉起与运维演练待 Phase 25。

### 2.12 Backup Restore

- 交付：BackupManager/RestoreManager，双工件 = 元数据快照（条目 + 生命周期）
  + MVCC 索引；restoreIncremental 水位重放；
- ADR：0080 / 0095 / 0097；
- 能力：全状态恢复（不降级）、tombstone/多版本语义、截断容忍、缺文件
  快速失败、destroy→restore→事务可读闭环；
- 测试：34 项（状态矩阵/规模/故障/闭环）+ 快照层 41 项；
- 风险：全量快照无调度/保留、离线恢复无 PITR、备份未加密、无跨机演练；
- 结论：正确性闭环成立，运维完整度（调度/加密/跨机）为 GA 后增强。

### 2.13 Rolling Upgrade

- 交付：UpgradeCoordinator（逐节点：quorum 检查 → 升级 → 追平等待 →
  下一节点；quorum 丢失/超时/中断即中止）；
- ADR：0096 / 0098；
- 测试：23 项（全量滚动、quorum 丢失位置矩阵、追平超时参数化、规模
  0/1/2/3/5/8、中断/异常、零超时即时检查）；
- 联动：优雅停机 + PDB(minAvailable=2) + readiness 探针；
- 风险：容器/K8s 级滚动未在真实集群执行、caughtUp 需接真实 Raft 复制进度、
  无版本回滚自动化、升级中性能降级未量化；
- 结论：协调器语义完整可测，真实执行与回滚自动化待 Phase 25。

## 3. ADR

| ADR | 主题 |
| --- | --- |
| 0095 | Transaction Metadata Multi-Raft |
| 0096 | Production Runtime Lifecycle |
| 0097 | Backup Restore Strategy |
| 0098 | Online Upgrade Strategy |

## 4. 关键决策与修复

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

## 5. 测试与混沌覆盖

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

## 6. 基准（进程内 JVM 等价，Windows localhost）

| 指标 | 目标 | 实测 |
| --- | ---: | --- |
| Gateway SET（transaction） | >100K ops/s | 144–175K ✅ |
| Cross Region Txn | >50K txn/s | 45–83K（峰值达标，均值如实记录） |
| Leader failover | <500ms | 164–303ms ✅ |
| Transaction recovery | <1s | ≈3ms ✅ |
| Lock resolve（500 锁） | — | 19–36ms |

## 7. 局限（不隐藏）

1. 元数据 Multi-Raft 为进程内 Raft 组（LocalRaftTransport），三节点网络化
   传输仍待跨机验证（登记 TD-050）；
2. 真实 Docker 磁盘混沌（dmsetup/fio/fallocate）与 CI 容器 E2E 需
   Linux + Docker Runner 执行，交付物（工作流、compose、清单）已就绪
   （TD-048/TD-049 登记为「交付物完成、执行待 Runner」）；
3. 基准为 JVM 进程内 + localhost，未包含跨机网络与真实磁盘冷启动；
4. 滚动升级的「升级中写入不丢失」由 JVM 等价验证覆盖，容器级验证待 CI。

## 8. 下一步

- 在 GitHub Actions Linux Runner 上触发 transaction-e2e 工作流；
- 元数据 Multi-Raft 网络化（Netty RPC 传输 + 持久化 Raft 日志）；
- K8s 清单的集群内 e2e（kind/k3s）与备份恢复演练。
