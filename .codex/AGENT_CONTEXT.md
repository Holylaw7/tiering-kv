# AGENT_CONTEXT — 项目长期上下文

> 每次会话开始时阅读本文档与仓库状态，快速恢复上下文。

## 1. 项目概况

Tiering-KV：Mini Redis 冷热分层存储引擎。核心能力：
RESP 协议兼容、内存 + 磁盘冷热分层、LFU/ARC 热度管理、异步冷热迁移、
Bitcask/LSM 持久化、高并发网络、mmap 零拷贝、分段锁/无锁、
Bloom Filter、自研 Memory Pool。

当前定位：高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎（RESP + WAL +
MemTable + SSTable + LFU/ARC + 自动调度 + Key Sharding + 分布式集群，
Phase 1–11 完成）；pub/sub、RESP3、正式性能基线为后续演进目标。

Phase 1 已交付命令：PING / ECHO / SET / GET / DEL / EXISTS（RESP2）。

Phase 2 已交付：StorageEngine SPI、64 段 SkipList MemTable、分段读写锁、
TTL（惰性 + 主动）、MemoryManager（配额 + 淘汰回调接口）、有序迭代器。

Phase 3 已交付：HotnessTracker / FrequencyCounter（LFU + 周期衰减）、
ARCPolicy（T1/T2/B1/B2 原型）、EvictionManager、MigrationCallback、
TrackingStorageEngine（访问事件装饰器）。

Phase 4 已交付：WAL 持久化层（写前日志 + 崩溃恢复 + checkpoint），
写路径 = WAL append → MemTable；默认 EVERY_SEC 策略。

Phase 5 已交付：冷存储层（SSTable + Bloom + Manifest + Compaction +
FlushManager + ColdMigration），完整冷热分层写路径（WAL → MemTable →
Flush → SSTable）。

Phase 6 已交付：自动调度（WatermarkManager + FlushScheduler +
MigrationScheduler + MigrationLog + BackPressureController + TierWorkerPool +
StorageMetrics + TieringStorageEngine），EvictionManager 异步化。

Phase 7 已交付：KeyShardExecutor（同键 FIFO / 异键并行）、
ResponseSequencer（RESP 保序）、MemTable 256 段、HotKeyDetector /
RequestCoalescer / HotKeyReadCache、ConcurrencyMetrics、异步命令执行。

Phase 8 已交付：mmap 冷读（MmapSSTableReader + FileChannel baseline）、
MemoryPool（DirectByteBuffer 池 + 统计）、BlockCache（LRU + off-heap +
失效）、IOStatistics；ColdStorageEngine 默认 mmap + cache。

Phase 9 已交付：三级生产基准（A/B/C）+ 容量模型 + 部署画像；结论：
A 级 GET 4.7M ops/s；B 级 pipeline64 峰值 218–231K（目标 500K 未达）；
C 级全链路 115–178K ops/s；瓶颈 = 网络/RESP/调度层。

Phase 10 已交付：响应批处理（pipeline64×500 → 465K ✅）+ 回调式执行 +
YAML 配置 + Metrics/INFO + ShutdownManager；Level C 154–326K 无回退。

Phase 11 已交付：分布式集群基础——16384 hash slot 路由（CRC16/CCITT）、
元数据服务（JOIN/拓扑/leader 变更）、最小真实 Raft（选举 + 心跳 + 日志
复制 + commit/apply + 随机化超时）、ReplicatedStorageEngine 复制适配器
（不改存储核心）、ClusterNode/ClusterClient、3 节点集成与故障转移测试
（51 项新测试）、集群基准（复制写 154K ops/s、选举 ≤310ms）。

Phase 12 已交付：分布式生产化——RaftLog 文件分段持久化（CRC32C +
SYNC/ASYNC/NONE + 尾部截断恢复）、RaftPersistentState（term/votedFor/
commitIndex）、SnapshotManager（自动压缩 + InstallSnapshot + 重启重放）、
Netty TCP RPC（RpcServer/RpcClient/RpcCodec/RequestId，连接复用 +
超时 + 幂等重试）、CommitNotifier 复制优化（滞后 13–35ms → <1ms）、
Slot 在线迁移（checkpoint 续传 + CRC 校验 + 原子切换）、3 节点真实
TCP 集群集成（故障转移 + 重启恢复）。

Phase 13 已交付：分布式优化——Raft 批量/流水线复制（batch +
inflight + group commit + 空闲即刷，TCP 吞吐 22K ops/s）、游标迁移
（MigrationCursor + PAUSED + `slot-{start}.cursor` CRC 续传，1KB 负载
216–245MB/s）、安全 RPC（TLS + Token 认证/过期 + TokenBucket 限流）、
元数据 Raft 化（MetadataRaftGroup 每副本独立状态机 + MetadataClient，
故障转移 115–290ms）、跨机部署文档。

Phase 14 已交付：生产加固——MemTable 批量写（applyBatch + WAL 批量）、
自适应 Flush/复制控制器 + 异步提案、HMAC-SHA256（防重放/轮换）+ mTLS、
元数据 Raft 持久化（重启拓扑保留 194ms）、故障注入与跨机文档；
101 项新测试；100B 迁移 18.3MB/s 与 Raft 37.3K ops/s 目标未达
（TD-033/034）。

Phase 15 已交付：生产验证——流式迁移（单次快照 + 游标 + 版本屏障，
100B 2.9→59.8 MB/s）、全异步批量提案（RaftNode.proposeBatch +
AsyncReplicationClient，1/64/256 写者 129/259/331K ops/s）、证书生命周期
（原子轮换 p50 13.5ms）、混沌验证（16 项，发现并修复 Raft 截断提案
虚假完成缺陷）、集群可观测性（ClusterMetricsRegistry + INFO CLUSTER）；
98 项新测试，全量回归 650 项全绿；100B/1KB 迁移未达 >100/>300 MB/s
（写路径 3 次拷贝，TD-033）。

Phase 16 已交付：Multi-Raft 架构演进——Region 模型（键范围 + epoch 路由
保护 + split/merge）、每 Region 独立 Raft 组（MultiRaftNode /
RaftGroupManager / 单端口 MultiRaftEndpoint 组前缀路由）、零拷贝批量写
（RawMutation 所有权转移 + applyRawBatch，100B 迁移 59.8→82.7 MB/s）、
放置控制（PlacementManager + leader 转移）、Region 指标 + INFO REGIONS、
混沌验证（ChaosClusterTest 20 项，发现并修复滞后副本回填缺陷）、
ClusterMain + Docker Compose + netem 跨机部署产物；138 项新测试，
全量回归 788/788 全绿；
100B/1KB 迁移仍未达 >100/>300 MB/s（TD-033，并行迁移 Phase 17）。

Phase 17 已交付：Region 生命周期闭环——SplitController（五阶段 + 写缓冲，
1M≈0.9s）、MergeController（故障可重试，1M≈0.7s）、并行迁移
（RegionTransferManager 按段分片 + chunk 检查点，100B 209.1MB/s）、
真实 leader 交接（TimeoutNow，24ms）、Redis Cluster Gateway
（GET/SET/MGET/MSET/CLUSTER SLOTS + MOVED）、BalanceScheduler
（自动均衡计划 + epoch 保护）、INFO RAFT / INFO MIGRATION 可观测性、
RegionChaosTest（10 项）；159 项新测试，全量回归 947/947 全绿。

Phase 18 已交付：分布式生产集成——统一路由（RoutingTable + RoutingCache +
RouteEpochGuard）、真实 TCP Redis Cluster 网关（GET 719K / SET 590K
ops/s）、Split/Merge 与 Raft 组联动（RegionRaftMigrationManager +
回滚/恢复）、生产化迁移（限速 + 自适应调度 + 指标）、三节点 compose +
CrossMachineChaosTest（20 项）、MetricsExporter（Prometheus）+ INFO
CLUSTER 聚合；165 项新测试，全量回归 1112/1112 全绿。

Phase 19 已交付：MVCC 与事务引擎——MvccStorageEngine（内存版本索引）+
TimestampOracle/HLC + SnapshotReader + Percolator 2PC（Prewrite/Commit/
Rollback）+ LockTable/ConflictDetector + TransactionManager/Coordinator
（跨 Region 2PC + 参与者键归属）+ TxnJournal（Raft）+ Recovery + GC +
INFO TRANSACTION + Prometheus；验收中修复 Raft 空心跳错误提交冲突条目的
共识缺陷（ADR-0077）；227 项新测试。
全量回归 1339/1339 全绿。

Phase 20 已交付：事务生产化与存储优化——批量 GC（BatchGcExecutor
107–285MB/s，TD-041 关闭）+ Redis 网关自动事务（GET/SET/DEL/MGET/MSET，
TD-042 关闭）+ 持久化 MVCC 索引（Writer/Reader/Snapshot/增量重建）+
PersistentTxnJournal + TxnRecoveryReplay（COMMIT 先落盘，恢复补完）+
锁过期墙上时钟修复 + INFO TRANSACTION/MVCC + Prometheus；181 项新测试。
全量回归 1523/1523 全绿。跨机 Docker+tc netem 因容器内 Maven 网络受限
未执行（TD-040/TD-043 登记）。

Phase 21 已交付：分布式事务网络化——DistributedTxnRouter /
RegionTxnClient / TxnParticipantClient（RPC 2PC）+ TransactionParticipant
（幂等状态机）+ TransactionMetadataService（Raft 元数据 + 崩溃恢复）+
MvccCompactor（在线压缩）+ 事务网络指标；202 项新测试；真实 Docker
三节点混沌（tc netem 100ms/5%/2%、分区、kill -9）执行成功；容器构建
修复 netty classifier / Main-Class / fat jar 三个缺陷。TD-043 部分关闭
（TCP 事务协议已覆盖，容器端到端待 Phase 22），TD-044 登记 disk 混沌未执行。
全量回归 1725/1725 全绿。

Phase 22 已交付：事务可靠性与生产运行时——decisionIndex + Raft-first
决策排序、TransactionLifecycleManager（TTL/心跳/超时 abort）、
LockResolver + TxnStatusCache、TCP 端到端运行时与 participant 重启恢复、
事务/锁指标升级；124 项新测试（低于 220 目标，TD-045 登记）；
磁盘故障 in-JVM 语义覆盖（TD-044 部分关闭，TD-046 登记真实注入受限）。
全量回归 1849/1849 全绿。

## 2. 当前状态

- 阶段：**Phase 19（MVCC & Transaction Engine）✅ 已完成**
  （Phase 0–18 全部完成）；
- 最近提交：Phase 19 基准（详见 git log）；
- 基线：tag `phase-0`；分支策略：feature/* 合并入 develop，main 保持稳定；
- 下一步：批量 GC 删除（TD-041）、Redis 自动事务化（TD-042）、
  Linux+Docker 跨机混沌（TD-040）（Phase 20，等待用户指令）。

项目里程碑：**19 阶段路线图全部完成（2026-08-10）**；定位 = 单机完整冷热
分层存储 + 分布式生产化 + 生产验证（RESP + Async Server + Shard + Memory +
LFU + WAL + LSM/SSTable + Bloom + Compaction + Migration + mmap +
BlockCache + Production Runtime + Raft 持久化 + TCP RPC + Snapshot +
Slot 迁移 + 批量复制 + 安全 RPC + 元数据 Raft + 流式迁移 + 异步提案 +
证书生命周期 + 混沌验证 + 集群可观测性 + Region + Multi-Raft + 零拷贝 +
Placement），能力矩阵全 ✅。

## 3. 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml，单模块起步） |
| 测试 | JUnit 5（单元）；tests/（集成）；benchmarks/（JMH 压测） |
| 网络 | Netty 4.1.115 事件循环（已引入，ADR-0006） |
| 内存层 | MemTable（64 段 SkipList + 分段读写锁，ADR-0007/0008/0009） |
| 缓存层 | LFU（默认）+ ARC 原型；EvictionManager + MigrationCallback（ADR-0010~0012） |
| 持久化 | WAL（CRC32C + segment 滚动 + checkpoint；EVERY_SEC 默认，ADR-0014~0016） |
| 冷存储 | SSTable + Bloom + Manifest + 全量合并（ADR-0017~0019） |
| 自动调度 | 水位 Flush + 异步迁移 + 背压 + MigrationLog（ADR-0020~0022） |
| 并发 | KeyShardExecutor + ResponseSequencer + 热点读缓存（ADR-0023~0025） |
| IO | mmap 零拷贝 + BlockCache + Off-Heap MemoryPool（ADR-0026~0028） |
| 生产基准 | 三级基准 + 容量模型 + 部署画像（ADR-0029~0031） |
| 生产化 | 批处理 + YAML 配置 + Metrics/INFO + 优雅停机（ADR-0032~0034） |
| 分布式 | 16384 哈希槽路由 + 元数据服务 + 最小 Raft + 复制适配器（ADR-0035~0038） |
| 分布式生产化 | RaftLog 持久化 + Snapshot + Netty RPC + 复制优化 + Slot 迁移（ADR-0039~0043） |
| 分布式优化 | 批量/流水线复制 + 游标迁移 + TLS/认证/限流 + 元数据 Raft（ADR-0044~0047） |
| 包结构 | `io.tieringkv.{network,protocol,command,storage,memory,cache,eviction,wal,sstable,compaction,scheduler,metrics,benchmark}` |

## 4. 关键决策（ADR）

| ADR | 决策要点 |
| --- | --- |
| [ADR-0001](adr/ADR-0001-project-architecture.md) | Java 17 + Maven 单模块；分层单向依赖；main/develop/feature 分支 |
| [ADR-0002](adr/ADR-0002-storage-engine.md) | StorageEngine SPI；Bitcask 先行（Phase 4）、LSM-Tree 演进（Phase 5）；WAL 独立 |
| [ADR-0003](adr/ADR-0003-concurrency-model.md) | Netty 事件循环 + key 分片执行 + 分段锁 + 异步迁移；禁止全局锁 |
| [ADR-0004](adr/ADR-0004-cache-policy.md) | LFU + ARC 混合热度管理 + Bloom Filter 防击穿 |
| [ADR-0005](adr/ADR-0005-persistence-format.md) | 自定义二进制持久化格式（版本化记录 + CRC32C） |
| [ADR-0006](adr/ADR-0006-resp-protocol.md) | RESP2 线上协议；inline 兼容；Phase 1 连接内同步执行 |
| [ADR-0007](adr/ADR-0007-memtable-data-structure.md) | MemTable 采用 SkipList（有序 + 迭代 + LSM 衔接） |
| [ADR-0008](adr/ADR-0008-memory-concurrency-model.md) | 64 段 Striped Lock（读写锁），无全局锁 |
| [ADR-0009](adr/ADR-0009-ttl-management-strategy.md) | TTL 惰性 + 主动混合（min-heap + 版本守卫） |
| [ADR-0010](adr/ADR-0010-hotness-tracking-strategy.md) | LFU 计数 + 周期衰减热度跟踪 |
| [ADR-0011](adr/ADR-0011-lfu-decay-algorithm.md) | 频率衰减：周期右移 ×0.5，懒计算 |
| [ADR-0012](adr/ADR-0012-arc-policy-evaluation.md) | ARC 原型（T1/T2/B1/B2 + p 自适应）评估 |
| [ADR-0013](adr/ADR-0013-tier-migration-interface.md) | TierMigration 结果码（SUCCESS/FAILED/RETRY），先迁移后删除 |
| [ADR-0014](adr/ADR-0014-wal-write-strategy.md) | WAL 写策略：ALWAYS / EVERY_SEC / NO（近似 group commit） |
| [ADR-0015](adr/ADR-0015-wal-record-format.md) | WAL 二进制记录格式 + CRC32C |
| [ADR-0016](adr/ADR-0016-crash-recovery-strategy.md) | 崩溃恢复：校验 → 重放 → 截断残尾 + checkpoint |
| [ADR-0017](adr/ADR-0017-cold-storage-strategy.md) | 冷层 = LSM 风格 SSTable + WAL 追加日志 |
| [ADR-0018](adr/ADR-0018-sstable-format.md) | SSTable 格式（Block/Index/Bloom/Footer + CRC） |
| [ADR-0019](adr/ADR-0019-compaction-strategy.md) | Size-Tiered 触发 + 全量 latest-wins 合并 |
| [ADR-0020](adr/ADR-0020-tier-scheduling-model.md) | 异步 worker 调度模型（事件循环不阻塞） |
| [ADR-0021](adr/ADR-0021-memory-watermark-policy.md) | 水位 70/85/95 + 队列阈值，CRITICAL 限写 |
| [ADR-0022](adr/ADR-0022-migration-persistence.md) | MigrationLog 持久化 + 启动恢复（幂等） |
| [ADR-0023](adr/ADR-0023-key-sharding-execution-model.md) | Key Sharding：同键 FIFO、异键并行、响应保序 |
| [ADR-0024](adr/ADR-0024-memtable-concurrency-strategy.md) | 256 段 Striped Lock；未验证 lock-free 不引入 |
| [ADR-0025](adr/ADR-0025-hot-key-mitigation.md) | 热点检测 + 本地读缓存 + 请求合并 |
| [ADR-0026](adr/ADR-0026-sstable-io-strategy.md) | mmap 生产读取 + FileChannel baseline |
| [ADR-0027](adr/ADR-0027-offheap-memory-strategy.md) | DirectByteBuffer 大小类池 + 统计 |
| [ADR-0028](adr/ADR-0028-block-cache-strategy.md) | Block Cache LRU + 池化缓冲 + 失效 |
| [ADR-0029](adr/ADR-0029-production-benchmark-methodology.md) | 三级基准方法与环境冻结 |
| [ADR-0030](adr/ADR-0030-capacity-model.md) | CPU/内存/磁盘/网络容量模型 |
| [ADR-0031](adr/ADR-0031-production-deployment-profile.md) | 生产部署画像（JVM/线程/WAL/水位） |
| [ADR-0032](adr/ADR-0032-response-batching-strategy.md) | 自适应响应批处理（batch=64 + 排空 flush） |
| [ADR-0033](adr/ADR-0033-request-response-memory-model.md) | 回调式执行 + 缓冲复用（对象削减） |
| [ADR-0034](adr/ADR-0034-production-service-lifecycle.md) | 启动/优雅停机生命周期（drain + WAL force + checkpoint） |
| [ADR-0035](adr/ADR-0035-cluster-sharding-strategy.md) | 16384 hash slot（CRC16）路由；对比 consistent hash / range |
| [ADR-0036](adr/ADR-0036-metadata-service-design.md) | 元数据服务：Raft 化设计（对比 ZK/静态配置） |
| [ADR-0037](adr/ADR-0037-replication-model.md) | Raft 复制：日志复制 + 多数派提交（对比 async/semi-sync） |
| [ADR-0038](adr/ADR-0038-failure-detection-strategy.md) | 心跳 + 随机化选举超时（100–180ms）故障检测 |
| [ADR-0039](adr/ADR-0039-raft-log-storage-format.md) | RaftLog 二进制格式 + 分段 + CRC + SYNC/ASYNC/NONE |
| [ADR-0040](adr/ADR-0040-raft-snapshot-strategy.md) | Snapshot 压缩 + InstallSnapshot + 重启重放 |
| [ADR-0041](adr/ADR-0041-distributed-rpc-design.md) | Netty TCP RPC：帧/关联/超时/重试/连接复用 |
| [ADR-0042](adr/ADR-0042-replication-lag-optimization.md) | CommitNotifier 立即补发，滞后 <5ms |
| [ADR-0043](adr/ADR-0043-slot-migration-strategy.md) | 在线迁移状态机 + checkpoint 续传 + CRC 校验 |
| [ADR-0044](adr/ADR-0044-raft-batch-replication.md) | 批量 AppendEntries + 流水线 + group commit |
| [ADR-0045](adr/ADR-0045-slot-cursor-migration.md) | MigrationCursor 单次扫描 + PAUSED + 游标文件 |
| [ADR-0046](adr/ADR-0046-rpc-security.md) | TLS + Token 认证/过期 + TokenBucket 限流 |
| [ADR-0047](adr/ADR-0047-metadata-raft.md) | 独立元数据 Raft 组 + 每副本状态机 |
| [ADR-0053](adr/ADR-0053-streaming-migration-design.md) | 流式迁移：单次快照 + 游标 + 版本屏障 + 动态 batch |
| [ADR-0054](adr/ADR-0054-async-proposal-pipeline.md) | 异步提案：有界队列 + 批量 proposeBatch + 背压 |
| [ADR-0055](adr/ADR-0055-certificate-lifecycle.md) | 证书加载/校验/过期/原子轮换 + 文件监听 |
| [ADR-0056](adr/ADR-0056-cluster-observability.md) | 集群指标 + INFO CLUSTER |
| [ADR-0057](adr/ADR-0057-region-model-design.md) | Region 模型 + epoch 路由保护 |
| [ADR-0058](adr/ADR-0058-multi-raft-design.md) | 多 Raft 组 + 单端口共享传输 |
| [ADR-0059](adr/ADR-0059-zero-copy-write-path.md) | 零拷贝批量写（所有权转移） |
| [ADR-0060](adr/ADR-0060-placement-control.md) | 放置控制（分布/均衡/leader 转移） |
| [ADR-0061](adr/ADR-0061-region-split-lifecycle.md) | Region 分裂生命周期 + 写缓冲 |
| [ADR-0062](adr/ADR-0062-region-merge.md) | Region 合并（PREPARE→TOMBSTONE） |
| [ADR-0063](adr/ADR-0063-parallel-region-migration.md) | 并行迁移（按段分片 + chunk 检查点） |
| [ADR-0064](adr/ADR-0064-real-leader-transfer.md) | 真实 leader 交接（TimeoutNow） |
| [ADR-0065](adr/ADR-0065-placement-auto-balance.md) | 自动均衡计划（epoch 保护） |
| [ADR-0066](adr/ADR-0066-unified-routing-model.md) | 统一路由（键范围 + slot + epoch） |
| [ADR-0067](adr/ADR-0067-region-raft-migration-lifecycle.md) | Split/Merge 与 Raft 组联动 |
| [ADR-0068](adr/ADR-0068-tcp-gateway-architecture.md) | 真实 TCP Redis Cluster 网关 |
| [ADR-0069](adr/ADR-0069-cross-machine-deployment.md) | 三节点部署 + tc netem |
| [ADR-0070](adr/ADR-0070-production-metrics.md) | Prometheus 指标 + INFO CLUSTER 聚合 |
| [ADR-0071](adr/ADR-0071-mvcc-data-model.md) | MVCC 数据模型 |
| [ADR-0072](adr/ADR-0072-timestamp-and-hlc.md) | 时间戳与 HLC |
| [ADR-0073](adr/ADR-0073-transaction-protocol.md) | Percolator 2PC 事务 |
| [ADR-0074](adr/ADR-0074-lock-and-conflict-detection.md) | 锁与冲突检测 |
| [ADR-0075](adr/ADR-0075-mvcc-garbage-collection.md) | MVCC GC |
| [ADR-0076](adr/ADR-0076-transaction-recovery.md) | 事务恢复 |

## 5. 仓库布局

```text
tiering-kv/
├── .codex/          # 工程控制中心（规则 + tasks/）
├── docs/
│   ├── requirements/  # requirements.md + acceptance.md
│   ├── architecture/  # overview + storage/network/concurrency
│   ├── adr/           # ADR-0001 ~ 0047
│   ├── design/        # protocol/memory/lsm/bitcask/eviction
│   ├── benchmark/     # 计划 + 报告占位
│   ├── review/        # 评审记录
│   └── operations/    # 部署/配置/故障
├── src/main/          # 模块骨架（network/protocol/command/storage/cache/…）
├── src/test/  tests/{unit,integration,stress,chaos}/
├── benchmarks/{throughput,latency,memory,migration}/
├── scripts/  config/  examples/  tools/  .github/workflows/
├── pom.xml            # Maven 构建（框架树未列出，保留）
├── README.md  ROADMAP.md  CHANGELOG.md  CONTRIBUTING.md  LICENSE  .gitignore
```

> 注：`src/main/<module>` 为框架骨架目录；Phase 1 落地 Java 代码时映射到 Maven
> 标准布局 `src/main/java/io/tieringkv/<module>/`（见 TD-004）。

## 6. Roadmap 状态

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ |
| 1 | RESP 协议 | ✅ |
| 2 | 内存 KV 核心 | ✅ |
| 3 | LFU / ARC | ✅ |
| 4 | Bitcask（WAL 子层） | ✅ |
| 5 | LSM Tree | ✅ |
| 6 | 冷热迁移 | ✅ |
| 7 | 并发优化 | ✅ |
| 8 | mmap / Memory Pool | ✅ |
| 9 | Benchmark | ✅ |
| 10 | 生产化完善 | ✅ |
| 11 | 分布式集群 | ✅ |
| 12 | 分布式生产化 | ✅ |
| 13 | 分布式优化 | ✅ |
| 14 | 生产加固 | ✅ |
| 15 | 生产验证 | ✅ |
| 16 | Multi-Raft 架构演进 | ✅ |
| 17 | Region 生命周期 | ✅ |
| 18 | 分布式生产集成 | ✅ |
| 19 | MVCC 与事务引擎 | ✅ |

## 7. 技术债

| 编号 | 描述 | 计划消除 |
| --- | --- | --- |
| TD-001 | 单 Maven 模块；模块耦合升高时评估拆分多模块 | Phase 7 前评估 |
| TD-002 | JDK 17 目标暂不采用虚拟线程 | Phase 7 评估 JDK 21 |
| TD-003 | 尚未引入架构约束测试（ArchUnit） | Phase 1 评估 |
| TD-004 | src/main 框架骨架目录与 Maven src/main/java 布局的映射 | ✅ 已关闭（Phase 1） |
| TD-005 | ARC 容量单位 entry count → byte 口径 | Phase 9 出 ADR |
| TD-006 | LFU 索引全局同步段 → Segment LFU + Async Buffer（Caffeine 思路） | Phase 7 |
| TD-007 | WAL 恢复单线程 → Phase 7 评估 parallel replay | Phase 7 |
| TD-008 | Checkpoint 全量快照 → Phase 5 演进 SSTable + Manifest | Phase 5 |
| TD-009 | 随机 GET 基准含 page cache；Phase 9 cold-cache 基准 | Phase 9 |
| TD-010 | pending 迁移缓冲未持久化 → Migration WAL / Pending Manifest | Phase 6 |
| TD-011 | Flush 手动触发 → memory watermark + FlushScheduler | Phase 6 |
| TD-012 | size-tiered 读放大 → 评估 leveled compaction | Phase 7 |
| TD-013 | 快照式 Flush → Active/Immutable MemTable 轮转（RocksDB 模型） | Phase 7 |
| TD-014 | 迁移队列准入控制 / 批量 / worker 动态扩缩容 | Phase 7/9 |
| TD-015 | 全量无锁读（ABA/回收/可见性）→ 暂缓，RWLock + Hot Cache 已够 | 验证后新 ADR |
| TD-016 | Phase 9 三级基准：A 内存 / B 服务端（pipeline 64）/ C 生产全链路 | Phase 9 |
| TD-017 | 动态重分片（task migration / routing version / double write） | Phase 10 |
| TD-018 | Hot Cache 增加 version check（当前 TTL 500ms 兜底） | Phase 10 评估 |
| TD-019 | 生产容量模型（吞吐/延迟/内存/磁盘），替代 IO 微优化 | Phase 9 |
| TD-020 | request→response 对象数优化（Future/Lambda/Callback 复用 + 批量写） | Phase 10 |
| TD-021 | JFR allocation / GC 对比作为 Phase 10 优化验收 | Phase 10 |
| TD-022 | Raft 日志内存态 → 文件分段 RaftLog + 快照 | ✅ 已关闭（Phase 12） |
| TD-023 | 进程内直调 → Netty TCP RPC + 超时重试 | ✅ 已关闭（Phase 12） |
| TD-024 | 复制滞后 13–35ms → CommitNotifier 立即补发（<1ms） | ✅ 已关闭（Phase 12） |
| TD-025 | 动态分片（slot 迁移）→ 在线迁移 + checkpoint | ✅ 已关闭（Phase 12） |
| TD-026 | 复制为同步串行 propose → 批量/流水线（9.2K ops/s） | ✅ 已关闭（Phase 13） |
| TD-027 | 迁移每批重建源快照 → 游标单次扫描 | ✅ 已关闭（Phase 13） |
| TD-028 | RPC 无 TLS/认证/限流 → 安全 RPC 层 | ✅ 已关闭（Phase 13） |
| TD-029 | 元数据单机 → Raft 元数据组 | ✅ 已关闭（Phase 13） |
| TD-030 | 小负载迁移 → 零拷贝批量写（100B 82.7MB/s） | ✅ 已关闭（Phase 16） |
| TD-031 | 复制 P50≈6ms → 异步提案 129K ops/s | ✅ 已关闭（Phase 15） |
| TD-032 | RPC 静态 token → HMAC 签名轮换；mTLS | Phase 14 |
| TD-033 | 100B 迁移 18.3→59.8→82.7MB/s（目标 >100） | Phase 17 并行迁移 |
| TD-034 | Raft 同步等待 37~68K → 异步提案 129K ops/s | ✅ 已关闭（Phase 15） |
| TD-035 | 真实跨机 tc netem 混沌 | Phase 16 部署产物交付；容器执行待 Linux+Docker |
| TD-036 | leader 转移未触发真实 Raft 交接 | ✅ 已关闭（Phase 17） |
| TD-037 | Region split/merge 未与数据搬迁联动 | Phase 18 |
| TD-038 | 网关 CLUSTER 命令子集 | Phase 18 |
| TD-039 | Region 键范围与 slot 区间路由未统一 | Phase 18 |
| TD-037 | Region split/merge 与数据搬迁联动 | ✅ 已关闭（Phase 18） |
| TD-039 | Region 键范围与 slot 区间路由未统一 | ✅ 已关闭（Phase 18） |
| TD-040 | 跨机容器混沌未执行（环境限制） | Phase 19 |
| TD-041 | MVCC GC 19–29MB/s（目标 >100） | Phase 20 |
| TD-042 | Redis 网关未接 MVCC 自动事务化 | Phase 20 |

## 8. 会话启动清单

1. `git status` + `git log --oneline -10`；
2. 阅读 README.md、ROADMAP.md、CHANGELOG.md；
3. 阅读 .codex/DEVELOPMENT_RULES.md、.codex/CODE_REVIEW_RULES.md、
   .codex/RELEASE_RULES.md；
4. 阅读 docs/adr/ 目录与 .codex/tasks/ 对应任务文件；
5. 对照 ROADMAP 与本文档确认当前阶段、未完成任务与技术债。
