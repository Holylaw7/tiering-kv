# ROADMAP

> 过程门：每次迭代（Phase）都执行「需求 → 设计 → ADR → 实现（TDD） → 测试 →
> 性能验证 → Git Commit」七个环节；下述 0–10 为交付路线图。

## 阶段总览

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ 完成（2026-08-09） |
| 1 | RESP 协议 | ✅ 完成（2026-08-09） |
| 2 | 内存 KV 核心 | ✅ 完成（2026-08-10） |
| 3 | LFU / ARC 热度管理 | ✅ 完成（2026-08-10） |
| 4 | Bitcask 持久化 | ✅ 完成（2026-08-10，WAL 层） |
| 5 | LSM Tree | ✅ 完成（2026-08-10） |
| 6 | 冷热迁移 | ✅ 完成（2026-08-10） |
| 7 | 并发优化 | ✅ 完成（2026-08-10） |
| 8 | mmap / Memory Pool | ✅ 完成（2026-08-10） |
| 9 | Benchmark 压力测试 | ✅ 完成（2026-08-10） |
| 10 | 生产化完善 | ✅ 完成（2026-08-10） |
| 11 | 分布式集群 | ✅ 完成（2026-08-10） |
| 12 | 分布式生产化 | ✅ 完成（2026-08-10） |
| 13 | 分布式优化 | ✅ 完成（2026-08-10） |
| 14 | 生产加固 | ✅ 完成（2026-08-10） |
| 15 | 生产验证 | ✅ 完成（2026-08-10） |
| 16 | Multi-Raft 架构演进 | ✅ 完成（2026-08-10） |
| 17 | Region 生命周期与分布式存储完善 | ✅ 完成（2026-08-10） |
| 18 | 分布式生产集成 | ✅ 完成（2026-08-10） |
| 19 | MVCC 与事务引擎 | ✅ 完成（2026-08-10） |
| 20 | 事务生产化与存储优化 | ✅ 完成（2026-08-10） |
| 21 | 分布式事务网络化与云生产 | ✅ 完成（2026-08-11） |
| 22 | 事务可靠性与生产运行时 | ✅ 完成（2026-08-11） |
| 23 | 事务运行时最终化 | ✅ 完成（2026-08-11） |
| 24 | 云原生生产发布（v1.0） | ✅ 完成（2026-08-11） |
| 25 | 控制面 GA 闭环（v1.0.0） | ✅ 完成（2026-08-11） |
| 26 | v1 发布冻结与企业就绪 | ✅ 完成（2026-08-11） |
| 27 | 跨地域复制与企业集成 | ✅ 完成（2026-08-11） |
| 28 | 多主复制与高级查询引擎 | ✅ 完成（2026-08-11） |
| 29 | 分布式查询与地域规模验证 | ✅ 完成（2026-08-11） |
| 30 | 动态重分片与全球运维 | ✅ 完成（2026-08-11） |
| 31 | 自治重分片与全球多活 | ✅ 完成（2026-08-11） |
| 32 | 生产接线与全球验证 | ✅ 完成（2026-08-11） |

## Phase 0 — 工程初始化 ✅

- 交付：Git 仓库（main/develop）、目录骨架、`.codex/` 工程控制中心、
  docs 知识库、Maven 骨架、CI 工作流、ADR-0001~0005。
- 验收：`mvn test` 通过；git 历史为 Conventional Commit；布局与框架标准一致。
- ADR：[0001](docs/adr/ADR-0001-project-architecture.md)、
  [0002](docs/adr/ADR-0002-storage-engine.md)、
  [0003](docs/adr/ADR-0003-concurrency-model.md)、
  [0004](docs/adr/ADR-0004-cache-policy.md)、
  [0005](docs/adr/ADR-0005-persistence-format.md)

## Phase 1 — RESP 协议 ✅

- 目标：RESP2 编解码（SET / GET / DEL / PING / ECHO / EXISTS 等）、协议错误处理。
- 交付：protocol（RespValue / Decoder / Encoder）、command（注册表 + 六命令）、
  network（Netty TCP 服务）、单元 + 集成 + 延迟冒烟测试（47 用例全绿）。
- ADR：[0006](docs/adr/ADR-0006-resp-protocol.md)（RESP2）。
- 基线：本机回环 GET P50=0.064ms / P95=0.151ms / P99=0.216ms
  （冒烟基准；Phase 9 以 JMH 建立正式基线）。

## Phase 2 — 内存 KV 核心 ✅

- 目标：MemTable（分段哈希表）、TTL 支持、内存配额与淘汰回调。
- 交付：StorageEngine SPI、64 段 SkipList MemTable、分段读写锁、TTLManager、
  MemoryManager、有序迭代器；命令层迁移完成（InMemoryKVStore 移除）。
- ADR：[0007](docs/adr/ADR-0007-memtable-data-structure.md)（SkipList）、
  [0008](docs/adr/ADR-0008-memory-concurrency-model.md)（分段锁）、
  [0009](docs/adr/ADR-0009-ttl-management-strategy.md)（TTL 混合策略）。
- 基准：存储层 GET（1M 数据集）P99≈2.5μs；网络端到端 GET P99≈0.19ms；
  100 线程并发写 0 失败（详见
  [memory-engine-report.md](docs/benchmark/memory-engine-report.md)）。

## Phase 3 — LFU / ARC 热度管理 ✅

- 目标：访问采样、LFU 衰减、ARC 自适应列表、冷热判定阈值。
- 交付：HotnessTracker / FrequencyCounter / LFUPolicy / ARCPolicy /
  EvictionManager / MigrationCallback / TrackingStorageEngine；命令层无感知接入。
- ADR：[0004](docs/adr/ADR-0004-cache-policy.md)（缓存策略）、
  [0010](docs/adr/ADR-0010-hotness-tracking-strategy.md)（热度跟踪）、
  [0011](docs/adr/ADR-0011-lfu-decay-algorithm.md)（LFU 衰减）、
  [0012](docs/adr/ADR-0012-arc-policy-evaluation.md)（ARC 评估）。
- 基准：LFU 查找 P99≈7.4μs、更新 P99≈8.8μs（100K 键 1M 访问）；
  淘汰决策 P99≈0.9μs（1M 条目，目标 <1ms，详见
  [cache-eviction-report.md](docs/benchmark/cache-eviction-report.md)）。

## Phase 4 — Bitcask 持久化（WAL 层） ✅

- 目标：追加写日志、全量内存索引、崩溃恢复、后台 merge。
- 交付：WAL 持久化层（WALManager / WALWriter / WALReader / LogSegment /
  SegmentManager / ChecksumValidator / RecoveryManager / CheckpointManager /
  WALStorageEngine）；写路径接入 MemTable；崩溃恢复与 checkpoint。
- 格式基线：[ADR-0005](docs/adr/ADR-0005-persistence-format.md)。
- ADR：[0014](docs/adr/ADR-0014-wal-write-strategy.md)（写策略）、
  [0015](docs/adr/ADR-0015-wal-record-format.md)（记录格式）、
  [0016](docs/adr/ADR-0016-crash-recovery-strategy.md)（崩溃恢复）。
- 基准：WAL append（buffered mode）P99≈6.8μs（100K/1M，目标 <1ms）；
  1M 记录恢复 0.57s（详见 [wal-report.md](docs/benchmark/wal-report.md)）。
- 备注：Bitcask 文件格式与 merge 在 Phase 5 完成（本阶段完成 WAL 子层）。

## Phase 5 — LSM Tree ✅

- 目标：MemTable → SSTable、层级合并、Bloom Filter。
- 交付：ColdStorageEngine（pending + 多表 + Manifest）、SSTableWriter /
  SSTableReader / Block / BlockIndex / BloomFilter / DiskIterator /
  CompactionManager / CompactionTask / FlushManager / ColdMigration；
  MemTable Flush（版本守卫）+ WAL checkpoint 接入。
- 格式基线：[ADR-0005](docs/adr/ADR-0005-persistence-format.md)。
- ADR：[0017](docs/adr/ADR-0017-cold-storage-strategy.md)（冷层策略）、
  [0018](docs/adr/ADR-0018-sstable-format.md)（SSTable 格式）、
  [0019](docs/adr/ADR-0019-compaction-strategy.md)（合并策略）。
- 基准：1M 写 104MB/s（目标 >100MB/s ✅）；随机 GET P99=0.021ms
  （目标 <5ms ✅）；Bloom FPR=0.82%（目标 <1% ✅）；详见
  [cold-report.md](docs/benchmark/cold-report.md)。
- 备注：全量合并（写放大 O(总数据)），Leveled 留 Phase 7。

## Phase 6 — 冷热迁移 ✅

- 目标：异步升降级迁移、迁移一致性协议、背压与重试。
- 交付：TieringController / WatermarkManager / FlushScheduler /
  MigrationScheduler / MigrationLog / BackPressureController /
  TierWorkerPool / StorageMetrics / TieringStorageEngine；EvictionManager
  异步化接入。
- ADR：[0020](docs/adr/ADR-0020-tier-scheduling-model.md)（调度模型）、
  [0021](docs/adr/ADR-0021-memory-watermark-policy.md)（水位策略）、
  [0022](docs/adr/ADR-0022-migration-persistence.md)（迁移持久化）。
- 基准：迁移 308K ops/s（目标 >50K ✅）；Flush 850K entries/s；内存压力下
  从未超配额（详见 [tiering-report.md](docs/benchmark/tiering-report.md)）。

## Phase 7 — 并发优化 ✅

- 目标：分段锁细化、无锁读路径、热点 key 缓解。
- 交付：KeyShardExecutor（同键 FIFO / 异键并行）、ResponseSequencer 保序、
  MemTable 256 段、HotKeyDetector + RequestCoalescer + HotKeyReadCache、
  ConcurrencyMetrics、CommandEngine.executeAsync。
- ADR：[0023](docs/adr/ADR-0023-key-sharding-execution-model.md)（分片执行）、
  [0024](docs/adr/ADR-0024-memtable-concurrency-strategy.md)（MemTable 并发）、
  [0025](docs/adr/ADR-0025-hot-key-mitigation.md)（热点键缓解）。
- 基准：GET 最高 6.3M ops/s、SET 4.5M ops/s、P99 <0.1ms；分片加速 2.79×；
  100 线程同键自增 0 lost update（详见
  [concurrency-report.md](docs/benchmark/concurrency-report.md)）。

## Phase 8 — mmap / Memory Pool ✅

- 目标：mmap 零拷贝、自研 Memory Pool、off-heap 缓冲。
- 交付：MmapSSTableReader（零拷贝块读）+ FileChannel baseline、
  MemoryPool（DirectBuffer 大小类池 + 统计）、BlockCache（LRU + 池化缓冲 +
  失效）、IOStatistics；ColdStorageEngine 默认 mmap + cache。
- ADR：[0026](docs/adr/ADR-0026-sstable-io-strategy.md)（SSTable IO 策略）、
  [0027](docs/adr/ADR-0027-offheap-memory-strategy.md)（Off-Heap 策略）、
  [0028](docs/adr/ADR-0028-block-cache-strategy.md)（Block Cache 策略）。
- 基准：随机读 P99 0.012–0.040ms（目标 <5ms）；缓存命中率 94.8%；mmap 较
  FileChannel 随机读提速 ~2×（详见 [io-report.md](docs/benchmark/io-report.md)）。

## Phase 9 — Benchmark ✅

- 目标：1k / 10k / 100k 连接、P50/P95/P99、内存对比 Redis。
- 交付：三级基准（A/B/C）+ 管道 RESP 客户端；phase9-memory / server /
  production 报告 + 容量模型 + 部署画像。
- 结果：A 级 GET 4.7M、SET 4.4M ops/s；B 级 pipeline64 峰值 218–231K
  （目标 500K 未达，瓶颈 = RESP/调度层）；C 级全链路 115–178K ops/s、
  P99 <5ms；Workload D 压力下内存受控。
- 方向（Phase 8 评审）：三级基准 A（内存）/ B（服务端 pipeline 64）/
  C（生产全链路）+ cold-cache 冷启动（TD-009）+ **生产容量模型**（TD-019）；
  IO 微优化阶段结束。

## Phase 10 — 生产化完善 ✅

- 目标：配置化、优雅停机、监控指标、故障演练、部署文档。
- 验收：达到工程完整性的 Mini Redis。
- 交付：响应批处理 + 回调式执行（Level B p64×500 218–231K → 465K ✅）、
  YAML 配置、Metrics/INFO、ShutdownManager（drain + WAL force + checkpoint）。
- ADR：[0032](docs/adr/ADR-0032-response-batching-strategy.md)（响应批处理）、
  [0033](docs/adr/ADR-0033-request-response-memory-model.md)（内存模型）、
  [0034](docs/adr/ADR-0034-production-service-lifecycle.md)（服务生命周期）。
- Phase 9 评审补充：协议/调度层优化——批量响应写、每请求对象数削减
  （TD-020）、ResponseSequencer 并发化、独立进程复测（预期 +20–40%）；
  以 JFR allocation/GC 为验收（TD-021）。

## Phase 11 — 分布式集群 ✅

- 目标：单机存储引擎演进为分布式 KV 基础（shard 集群 + 元数据 + Raft +
  复制 + 故障转移）。
- 交付：16384 hash slot 路由（HashSlotRouter / SlotTable / ShardGroup）、
  元数据服务（NodeRegistry / ShardRegistry / TopologyManager）、最小真实
  Raft（LeaderElection / ReplicationManager / RaftNode，Follower/Candidate/
  Leader + 心跳 + 日志复制 + commit/apply）、ReplicatedStorageEngine 适配器
  （不改 MemTable/WAL/SSTable）、ClusterNode / ClusterClient、3 节点集成
  与故障转移测试。
- ADR：[0035](docs/adr/ADR-0035-cluster-sharding-strategy.md)（哈希槽）、
  [0036](docs/adr/ADR-0036-metadata-service-design.md)（Raft 元数据）、
  [0037](docs/adr/ADR-0037-replication-model.md)（Raft 复制）、
  [0038](docs/adr/ADR-0038-failure-detection-strategy.md)（心跳/选举）。
- 测试：新增 51 项（Sharding 10 / Metadata 10 / Raft 21 / Failover 9 /
  集成 1），全量回归 288 项全绿。
- 基准：单分片复制写 154K ops/s（P99=0.027ms）、读 750K ops/s
  （P99=4μs）；路由开销 23–36ns/op；复制滞后 ≤35ms（心跳周期约束）；
  选举 124–310ms（目标 <5s ✅）；详见
  [cluster-report.md](docs/benchmark/cluster-report.md)。

## Phase 12 — 分布式生产化 ✅

- 目标：内存 Raft 原型 → 持久化 + 真实网络 + 快照 + 在线迁移。
- 交付：
  - RaftLog（分段二进制文件，MAGIC/VERSION/TERM/INDEX/COMMAND_TYPE/
    DATA/CRC32C，SYNC/ASYNC/NONE，尾部截断恢复）+ RaftPersistentState
    （term/votedFor/commitIndex）；
  - SnapshotManager / SnapshotWriter / SnapshotReader（快照 + 日志压缩 +
    InstallSnapshot 追赶）；
  - Netty RPC（RpcServer/RpcClient/RpcCodec/RequestId，连接复用 +
    超时 + 幂等重试 + 三类 Raft 消息）+ RaftTransport 抽象
    （Local/Netty 可替换）；
  - 复制优化：CommitNotifier 立即补发 commitIndex（滞后 13–35ms →
    <1ms，目标 <5ms ✅）；ReplicationTracker / FollowerProgress；
  - Slot 在线迁移（SlotMigrationManager / MigrationTask /
    MigrationCheckpoint，INIT→COPYING→VERIFYING→SWITCHING→DONE，
    checkpoint 续传 + CRC 校验 + 原子切换）。
- ADR：[0039](docs/adr/ADR-0039-raft-log-storage-format.md)（日志格式）、
  [0040](docs/adr/ADR-0040-raft-snapshot-strategy.md)（快照）、
  [0041](docs/adr/ADR-0041-distributed-rpc-design.md)（RPC）、
  [0042](docs/adr/ADR-0042-replication-lag-optimization.md)（复制优化）、
  [0043](docs/adr/ADR-0043-slot-migration-strategy.md)（迁移）。
- 测试：新增 77 项（RaftLog 21 / Snapshot 12 / RPC 19 / 迁移 11 /
  复制优化 5 / 快照集成 2 / TCP 集群集成 3 / 基准 4），全量回归
  369 项全绿（Phase 1–12）。
- 基准（[distributed-production-report.md](docs/benchmark/distributed-production-report.md)）：
  TCP 提交 1,359 ops/s（P50=0.65ms / P99=2.16ms）、复制滞后 <1ms、
  RPC 9.3K ops/s（P50=100μs，单连接）、迁移 16.1MB/s + 恢复 549ms。

## Phase 13 — 分布式优化 ✅

- 目标：吞吐、迁移效率、RPC 安全、元数据可靠性、跨机部署准备。
- 交付：
  - Raft 批量/流水线复制（RaftReplicationConfig：maxBatchEntries /
    maxBatchBytes / flushInterval / maxInflight；group commit；
    ReplicationTracker inflight/lastSent 跟踪）；
  - 游标迁移（MigrationCursor lastKey/lastVersion/checkpointOffset、
    PAUSED 状态、`slot-{start}.cursor` CRC 文件、pause/resume/recover）；
  - 安全 RPC（RpcSecurityConfig + SslContext TLS + RpcAuthInterceptor
    Token 认证/过期 + TokenBucket 限流 + AUTH/ERROR 帧）；
  - 元数据 Raft 化（MetadataRaftGroup / MetadataCodec / MetadataState
    每副本独立状态机 / MetadataClient，leader 故障转移元数据可用）；
  - 跨机部署文档（gateway/metadata/storage 角色、端口、YAML）。
- ADR：[0044](docs/adr/ADR-0044-raft-batch-replication.md)（批量复制）、
  [0045](docs/adr/ADR-0045-slot-cursor-migration.md)（游标迁移）、
  [0046](docs/adr/ADR-0046-rpc-security.md)（RPC 安全）、
  [0047](docs/adr/ADR-0047-metadata-raft.md)（元数据 Raft）。
- 测试：新增 82 项（批量复制 15 / 迁移游标 15 / RPC 安全 19 / 元数据
  Raft 24 / 集成 5 / 基准 4），全量回归最终统计见评审报告。
- 基准（[phase13-report.md](docs/benchmark/phase13-report.md)）：
  复制吞吐 22,169 ops/s（>5000 ✅）、迁移 216–245MB/s（>100MB/s ✅）、
  RPC 安全开销 +50–70%、元数据故障转移 115–290ms。

## Phase 14 — 生产加固 ✅

- 交付：MemTable.applyBatch（批量写 + WAL 批量追加）、
  AdaptiveFlushController、ReplicationController + putAsync、
  HMAC-SHA256（防重放/轮换）+ mTLS、元数据 Raft 持久化
  （FileRaftLog + MetadataSnapshot）、故障注入（5/5）与跨机指南。
- ADR：[0048](docs/adr/ADR-0048-memtable-batch-write.md)（批量写）、
  [0049](docs/adr/ADR-0049-adaptive-flush-policy.md)（自适应 Flush）、
  [0050](docs/adr/ADR-0050-adaptive-raft-replication.md)（自适应复制）、
  [0051](docs/adr/ADR-0051-rpc-security-upgrade.md)（安全升级）、
  [0052](docs/adr/ADR-0052-metadata-log-persistence.md)（元数据持久化）。
- 基准（[phase14-production-report.md](docs/benchmark/phase14-production-report.md)）：
  100B 迁移 18.3MB/s 与 Raft 37.3K ops/s 未达目标（TD-033/034，已如实记录）。

## Phase 15 — 生产验证 ✅

- 目标：TD-030（迁移 >100MB/s）、TD-031（Raft >100K）、TD-035
  （真实跨机混沌）。
- 交付：
  - 流式迁移（StreamingMigrator 单次快照 + MigrationStreamCursor 游标 +
    版本屏障 + 动态 batch，修复每批 O(N) 快照重建）；
  - 全异步提案（RaftNode.proposeBatch + AsyncReplicationClient 有界队列/
    背压/批量 drain/leader 重试）；
  - 证书生命周期（CertificateManager 原子轮换 + CertificateWatcher）；
  - 混沌验证（ChaosValidationTest 16 项：延迟/丢包/分区/磁盘慢/kill/
    混合故障/法定人数丢失）；
  - 集群可观测性（ClusterMetricsRegistry + INFO CLUSTER）。
- ADR：[0053](docs/adr/ADR-0053-streaming-migration-design.md)（流式迁移）、
  [0054](docs/adr/ADR-0054-async-proposal-pipeline.md)（异步提案）、
  [0055](docs/adr/ADR-0055-certificate-lifecycle.md)（证书生命周期）、
  [0056](docs/adr/ADR-0056-cluster-observability.md)（集群可观测性）。
- 测试：新增 98 项（迁移 19 / 异步 Raft 21 / 证书 15 / 混沌 16+1 /
  可观测性 15 / 基准 11）；全量回归 650 项全绿；发现并修复 Raft 缺陷
  （截断提案虚假完成）。
- 基准（[phase15-production-validation-report.md](docs/benchmark/phase15-production-validation-report.md)）：
  迁移 100B 59.8 / 1KB 173.3 / 10KB 589.8 MB/s；Raft 1/64/256 写者
  129/259/331K ops/s（目标 100K/200K ✅），P99 0.009/3.071/9.824ms
  （1/64 写者 <10ms ✅）；混沌选举恢复 155ms；TLS 轮换 p50 13.5ms。
- 未达标（如实记录）：100B/1KB 迁移未达 >100/>300 MB/s（写路径每条目
  3 次数组拷贝 → Phase 16 零拷贝批量写）。

## Phase 16 — Multi-Raft 架构演进 ✅

- 目标：Shard + 单 Raft → Region + Multi-Raft + Placement；解决 TD-033；
  跨机部署产物。
- 交付：
  - Region 模型（Region / RegionEpoch / RegionManager：create/split/
    merge/route + epoch guard + tombstone 审计）；
  - Multi-Raft（MultiRaftNode / RaftGroupManager / MultiRaftEndpoint
    单端口多组 / MultiRaftTransport）；
  - 零拷贝批量写（RawMutation / applyRawBatch / KeyValueEntry owned /
    SkipList.putAndGetOld / StreamingMigrator 切换）；
  - Placement（PlacementManager 分布/均衡/leader 转移）+ Region 指标 +
    INFO REGIONS；
  - 混沌验证（ChaosClusterTest 20 项）+ ClusterMain + Docker Compose +
    tc netem 脚本。
- ADR：[0057](docs/adr/ADR-0057-region-model-design.md)（Region 模型）、
  [0058](docs/adr/ADR-0058-multi-raft-design.md)（Multi-Raft）、
  [0059](docs/adr/ADR-0059-zero-copy-write-path.md)（零拷贝写路径）、
  [0060](docs/adr/ADR-0060-placement-control.md)（放置控制）。
- 测试：新增 138 项（Region 34 / Multi-Raft 32 / Zero-Copy 21 /
  Chaos 21 / Placement+可观测性 23 / 基准 6）。
- 基准（[phase16-multiraft-report.md](docs/benchmark/phase16-multiraft-report.md)）：
  零拷贝迁移 100B 82.7 / 1KB 223.1 / 10KB 631.0 MB/s（100B/1KB 目标
  未达，如实记录）；Multi-Raft 1/2/4 组 110/222/404K ops/s（线性扩展
  ✅）；TCP 单端口 P99 0.551ms；故障恢复 p50 183ms。
- 缺陷修复：新 leader 以非空日志当选后不回填滞后 follower
  （心跳不匹配回退 nextIndex）+ 回归测试。

## Phase 17 — Region 生命周期与分布式存储完善 ✅

- 目标：Region Split/Merge 闭环、并行迁移 >150MB/s、真实 leader 交接
  <500ms、Redis Cluster 网关、自动均衡计划、≥900 测试全绿。
- 交付：
  - SplitController（五阶段 + 写缓冲 + epoch+1 + 路由原子切换）；
  - MergeController（邻接校验 + 数据搬迁 + 元数据合并 + tombstone）；
  - 并行迁移（RegionTransferManager 按段分片 + ChunkCheckpoint +
    pause/resume/retry）；
  - 真实 leader 交接（RaftNode.transferLeadership + TimeoutNow RPC +
    LeaderTransferManager）；
  - RedisClusterGateway（GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS +
    MOVED）；
  - BalanceScheduler/BalancePlan（region/leader/disk/cpu 压力 +
    epoch 保护）；
  - 可观测性：INFO RAFT / INFO MIGRATION + 指标；
  - RegionChaosTest（10 项：分裂并发写/合并故障恢复/延迟丢包交接/
    Region 隔离）。
- ADR：[0061](docs/adr/ADR-0061-region-split-lifecycle.md)（Split）、
  [0062](docs/adr/ADR-0062-region-merge.md)（Merge）、
  [0063](docs/adr/ADR-0063-parallel-region-migration.md)（并行迁移）、
  [0064](docs/adr/ADR-0064-real-leader-transfer.md)（leader 交接）、
  [0065](docs/adr/ADR-0065-placement-auto-balance.md)（自动均衡）。
- 测试：新增 159 项（Split 29 / Merge 24 / ParallelMigration 22 /
  LeaderTransfer 16 / Gateway 24 / Balance 16 / Observability 10 /
  RegionChaos 10 / Benchmark 5 等）；全量回归 947/947 全绿。
- 基准（[phase17-region-report.md](docs/benchmark/phase17-region-report.md)）：
  Split 1M≈0.9s（<10s ✅）、Merge 1M≈0.7s（<20s ✅）、并行迁移 100B
  209.1MB/s（>150 ✅）、Leader Transfer 24ms（<500ms ✅）、Gateway
  GET/SET 3.68M/1.67M ops/s（>100K/50K ✅）。

## Phase 18 — 分布式生产集成 ✅

- 目标：统一路由、真实 TCP 网关、Split/Merge 与 Raft 联动、生产化
  迁移、三节点部署、完整可观测性；测试 >1100。
- 交付：
  - UnifiedRoutingLayer（RoutingTable/RoutingCache/RouteEpochGuard）；
  - NettyClusterGateway（真实 TCP，GET 719K / SET 590K ops/s，
    MOVED/ASK/TRYAGAIN，CLUSTER SLOTS/NODES）；
  - RegionRaftMigrationManager（split/merge + 子 Raft 组 + 路由切换 +
    回滚/恢复）；
  - MigrationScheduler + ByteRateLimiter + 迁移指标；
  - docker-compose.cluster.yml + CrossMachineChaosTest（20 项）；
  - MetricsExporter（Prometheus）+ ProductionInfo（INFO CLUSTER 聚合）。
- ADR：[0066](docs/adr/ADR-0066-unified-routing-model.md)（统一路由）、
  [0067](docs/adr/ADR-0067-region-raft-migration-lifecycle.md)（Raft 迁移）、
  [0068](docs/adr/ADR-0068-tcp-gateway-architecture.md)（TCP 网关）、
  [0069](docs/adr/ADR-0069-cross-machine-deployment.md)（跨机部署）、
  [0070](docs/adr/ADR-0070-production-metrics.md)（生产指标）。
- 测试：新增 165 项（Routing 23 / Gateway 31 / Split-Raft 33 /
  Merge-Raft 25 / Migration 20 / Chaos 20 / Metrics 11 / Benchmark 2）；
  全量回归 1112/1112 全绿。
- 基准（[phase18-production-report.md](docs/benchmark/phase18-production-report.md)）：
  Gateway GET/SET 719K/590K ops/s（>500K/200K ✅）、迁移 100B/1KB
  209.1/986.0 MB/s（>100/300 ✅）、Split/Merge 1M ~0.9/~0.7s。

## Phase 19 — MVCC 与事务引擎 ✅

- 交付：MVCC 数据模型 + TimestampOracle/HLC + SnapshotReader +
  Percolator 2PC（Prewrite/Commit/Rollback）+ LockTable/ConflictDetector +
  TransactionManager/Coordinator + TxnJournal（Raft）+ Recovery + GC +
  指标（INFO TRANSACTION + Prometheus）。
- ADR：[0071](docs/adr/ADR-0071-mvcc-data-model.md)（MVCC）、
  [0072](docs/adr/ADR-0072-timestamp-and-hlc.md)（时间戳）、
  [0073](docs/adr/ADR-0073-transaction-protocol.md)（事务协议）、
  [0074](docs/adr/ADR-0074-lock-and-conflict-detection.md)（锁/冲突）、
  [0075](docs/adr/ADR-0075-mvcc-garbage-collection.md)（GC）、
  [0076](docs/adr/ADR-0076-transaction-recovery.md)（恢复）、
  [0077](docs/adr/ADR-0077-raft-heartbeat-commit-bound.md)（心跳提交上界）。
- 测试：新增 227 项；全量回归 1339/1339 全绿（目标 >1290 ✅）。
- 基准（[phase19-mvcc-report.md](docs/benchmark/phase19-mvcc-report.md)）：
  GET 3.1–4.7M ops/s（>500K ✅）、单区事务 70.8–204.6K txn/s
  （>100K ✅ 最佳轮）、冲突 2.1–7.6M ops/s（>500K ✅）、GC 19–29MB/s
  （>100 未达，TD-041）。

## Phase 20 — 事务生产化与存储优化 ✅

- 交付：批量 GC（BatchGcExecutor 107–285MB/s）+ 网关自动事务
  （AutoTransactionExecutor / TransactionCommandHandler）+ 持久化 MVCC
  索引（Writer/Reader/Snapshot/增量重建）+ PersistentTxnJournal +
  TxnRecoveryReplay（COMMIT 先落盘，恢复补完）+ 锁过期墙上时钟修复 +
  INFO TRANSACTION/MVCC + Prometheus 指标。
- ADR：[0078](docs/adr/ADR-0078-mvcc-batch-gc.md)（批量 GC）、
  [0079](docs/adr/ADR-0079-redis-auto-transaction.md)（自动事务）、
  [0080](docs/adr/ADR-0080-persistent-mvcc-index.md)（索引持久化）、
  [0081](docs/adr/ADR-0081-transaction-journal-raft-persistence.md)
  （事务日志 Raft）、[0082](docs/adr/ADR-0082-cross-machine-transaction-validation.md)
  （跨机验证）。
- 测试：新增 181 项；全量回归 1523/1523 全绿（0 failures）。
- 基准（[phase20-report.md](docs/benchmark/phase20-report.md)）：
  GC 107–285MB/s（>100 ✅）、网关 GET 2.0–6.9M / SET 141–389K ops/s、
  单区事务 324–651K txn/s、跨区 62–158K txn/s、恢复 1–4ms。

## Phase 21 — 分布式事务网络化与云生产 ✅

- 交付：DistributedTxnRouter / RegionTxnClient / TxnParticipantClient
  （RPC 2PC）+ TransactionParticipant（幂等状态机）+
  TransactionMetadataService（Raft 元数据 + 崩溃恢复）+
  MvccCompactor（在线压缩）+ 事务网络指标 + 真实 Docker 混沌
  （tc netem / 分区 / kill -9）。
- ADR：[0083](docs/adr/ADR-0083-distributed-transaction-protocol.md)、
  [0084](docs/adr/ADR-0084-transaction-metadata-raft.md)、
  [0085](docs/adr/ADR-0085-online-mvcc-compression.md)、
  [0086](docs/adr/ADR-0086-cross-machine-chaos-validation.md)。
- 测试：新增 202 项；全量回归 1725/1725 全绿（0 failures）。
- 基准（[phase21-report.md](docs/benchmark/phase21-report.md)）：
  单区 58.7–116.4K、多区 88.1–110.7K txn/s、恢复 0–0ms、
  leader 恢复 156–276ms。
- 混沌（[phase21-real-chaos-report.md](docs/testing/phase21-real-chaos-report.md)）：
  Docker 三节点 + netem/分区/kill -9 存活恢复；TD-044 登记 disk 混沌未执行。

## Phase 22 — 事务可靠性与生产运行时 ✅

- 交付：decisionIndex + Raft-first 决策排序（ADR-0087）、
  TransactionLifecycleManager / TxnTimeoutScheduler / TxnHeartbeatManager
  （ADR-0088）、LockResolver + TxnStatusCache（ADR-0089）、TCP 端到端
  事务运行时（ADR-0090）、事务/锁指标升级；
- 基准（[phase22-report.md](docs/benchmark/phase22-report.md)）：
  SET 128–150K、GET 3.9–25M、跨区 33.6–59.7K、恢复 0–15ms、
  锁解析 50–129ms；
- 测试：新增 124 项；全量回归 1849/1849 全绿（0 failures）。

## Phase 23 — 事务运行时最终化 ✅

- 交付：runtime 角色（gateway/coordinator/participant/metadata）+
  compose.transaction.yml + 生命周期持久化（ADR-0091）+
  LockResolver RPC（ADR-0092）+ 磁盘混沌语义（ADR-0094）+
  生产配置冻结（ADR-0093）。
- 测试：新增 158 项；全量回归 **2007/2007 全绿**（TD-045 关闭）。

## Phase 24 — 云原生生产发布（v1.0） ✅

- 交付：事务元数据 Multi-Raft（`txn/meta/`：TxnMetadataNode /
  TxnMetadataClient / MetadataSnapshotManager，ADR-0095）、健康探针与
  优雅停机（RuntimeHealth / GracefulShutdown，ADR-0096）、备份恢复
  （BackupManager / RestoreManager，ADR-0097）、滚动升级
  （UpgradeCoordinator，ADR-0098）、Kubernetes 生产清单
  （deploy/kubernetes/tiering-kv/）、CI 容器 E2E 工作流
  （.github/workflows/transaction-e2e.yml）、v1.0 发布说明。
- 关键修复：TxnRpcCodec 64KB 长度前缀溢出（1MB 值往返）、并发快照
  一致性、零超时 drain/catchup 语义。
- 测试：新增 231 项；全量回归 **2238/2238 全绿**（目标 ≥2200 ✅）。
- 基准（[phase24-final-production-report.md](
  docs/benchmark/phase24-final-production-report.md)）：事务 SET
  144–175K ops/s、跨区事务 45–83K txn/s、leader failover 164–303ms、
  恢复 ≈3ms。

## Phase 25 — 控制面 GA 闭环（v1.0.0） ✅

- 交付：元数据 Multi-Raft 网络化（ADR-0099，TD-050 关闭：TxnMetadataNode
  接入 MultiRaftEndpoint + FileRaftLog/RaftPersistentState/Snapshot，
  META_PROPOSE/META_STATUS RPC + 异步响应）、CI 容器故障注入
  （ADR-0100）、真实块设备混沌脚本与门控测试（ADR-0101）、kind 集群内
  验证（ADR-0102）。
- 测试：新增 170 项；全量回归 **2408/2408 全绿**（目标 ≥2400 ✅，
  6 项容器门控本地跳过）。
- 基准（[phase25-final-ga-report.md](
  docs/benchmark/phase25-final-ga-report.md)）：元数据提案 657–1077
  ops/s、并发 1393 ops/s、failover 110–118ms、日志/快照恢复 ≈250ms
  （不含端口等待，进程内 TCP 口径）。
- Runner 待执行：TD-048（容器 E2E）、TD-049（块设备磁盘）、K8s 集群内
  演练（交付物全部就绪）。

## Phase 26 — v1 发布冻结与企业就绪 ✅

- 交付：协议冻结（ADR-0103，RESP2/RPC v1/存储格式 v1 + 兼容矩阵）、
  PITR（ADR-0104，WALArchive/Checkpoint/RestoreTimeline）、CDC
  （ADR-0105，exactly-once checkpoint）、企业安全（ADR-0106，RBAC +
  令牌生命周期）、Kubernetes Operator（ADR-0107，CRD + Planner/
  Controller）、tierctl CLI、v1 发布流水线（release.yml）。
- 测试：新增 293 项；全量回归 **2701/2701 全绿**（目标 ≥2700 ✅）。
- 基准（[v1-final-production-report.md](
  docs/benchmark/v1-final-production-report.md)）：PITR append
  2.7–3.2K ops/s、CDC append 5.9–6.5K ops/s、PITR restore 21–38ms、
  Security 1–10M ops/s、Operator plan 1–5M ops/s（进程内口径）。

## Phase 27 — 跨地域复制与企业集成 ✅

- 交付：Multi-Region Replication（ADR-0108）、Geo Distributed
  Transaction（ADR-0109）、RBAC 网关/RPC 接线（ADR-0110）、PITR 保留
  策略（ADR-0111）、CDC 多消费者组（ADR-0112）、SQL/Vector/SaaS 探索
  原型（ADR-0113）。
- 测试：新增 264 项；全量回归 **2965/2965 全绿**（目标 ≥2950 ✅）。
- 基准（[phase27-report.md](docs/benchmark/phase27-report.md)）：
  SYNC 复制 100–250K ops/s、RBAC 1–10M ops/s、SQL 点查 0.36–0.5M、
  Vector topK 5.5–14.5K ops/s（进程内口径）。
- 后续：双向复制/CRDT、完整 SQL、HNSW 生产化、SaaS 多租户（Phase 28+）。

## Phase 28 — 多主复制与高级查询引擎 ✅

- 交付：双向复制 + CRDT（ADR-0114）、两地三中心容灾（ADR-0115）、
  SQL 引擎（ADR-0116）、HNSW + 混合检索（ADR-0117）、SaaS 多租户
  （ADR-0118）、RPC 帧级令牌（ADR-0119）、v1.1 发布流水线。
- 测试：新增 251 项；全量回归 **3216/3216 全绿**（目标 ≥3200 ✅）。
- 基准（[phase28-production-report.md](
  docs/benchmark/phase28-production-report.md)）：CRDT 1–2.5M ops/s、
  双向写 33–167K ops/s、SQL JOIN 1K×1K 1–5ms、HNSW 100×topK5 ≈38ms。
- 后续：分布式 SQL、向量分片、Geo CRDT 大规模验证、三地五中心
  （Phase 29+）。

## Phase 29 — 分布式查询与地域规模验证 ✅

- 交付：分布式 SQL（ADR-0120）、分布式向量索引（ADR-0121）、Geo CRDT
  规模验证（ADR-0122）、三地五中心与全球读（ADR-0123）、SaaS 计量/
  市场（ADR-0124）、v1.2 发布流水线（ADR-0125）、分布式告警。
- 测试：新增 255 项；全量回归 **3471/3471 全绿**（目标 ≥3450 ✅）。
- 基准（[phase29-production-report.md](
  docs/benchmark/phase29-production-report.md)）：分片计划 7.7–27K
  ops/s、CRDT 10 万键 ≈109ms、全局读 0.1–1M ops/s、JOIN 1K×1K ≈11ms。
- 后续：动态重分片、向量迁移落地、SQL 触发 2PC、全球读水位联动
  （Phase 30+）。

## Phase 30 — 动态重分片与全球运维 ✅

- 交付：动态重分片（ADR-0126）、向量分片迁移（ADR-0127）、SQL 写事务
  （ADR-0128）、全球读水位联动（ADR-0129）、账单导出（ADR-0130）、
  v1.3 发布流水线（ADR-0131）、查询优化/容量模型（Goal 7/8）。
- 测试：新增 271 项；全量回归 **3742/3742 全绿**（目标 ≥3700 ✅）。
- 基准（[phase30-production-report.md](
  docs/benchmark/phase30-production-report.md)）：分片路由 1–10M
  ops/s、迁移 1–5M ops/s、SQL 写事务 6.25K–143K txn/s。
- 后续：负载驱动自动重分片、SQL 写 2PC 端到端、向量迁移双写联动、
  账单周期滚动（Phase 31+）。

## Phase 31 — 自治重分片与全球多活 ✅

- 交付：负载驱动自动重分片（ADR-0132）、SQL 写 2PC 端到端（ADR-0133）、
  向量双写迁移（ADR-0134）、全球 Active-Active（ADR-0135）、账单周期
  滚动/多云部署（ADR-0136）、企业控制台（ADR-0137）、v1.4 发布流水线。
- 测试：新增 258 项；全量回归 **4000/4000 全绿**（目标 ≥3950 ✅）。
- 基准（[phase31-production-report.md](
  docs/benchmark/phase31-production-report.md)）：自动重分片 1–10M
  ops/s、Active-Active 写 25–200K ops/s、SQL 2PC 16.7K–167K txn/s。
- 后续：SQL 2PC 真实接线、控制台 REST 服务、自动重分片并发迁移、
  全球多活网关冲突审计（Phase 32+）。

## Phase 32 — 生产接线与全球验证 ✅

- 交付：SQL 写 2PC 生产接线（ADR-0138）、控制台 REST 服务（ADR-0139）、
  并发自动重分片（ADR-0140）、网关冲突审计（ADR-0141）、自动选主与
  数据主权（ADR-0143）、v1.5 发布流水线（ADR-0142）。
- 测试：新增 251 项；全量回归 **4251/4251 全绿**（目标 ≥4200 ✅）。
- 基准（[phase32-production-report.md](
  docs/benchmark/phase32-production-report.md)）：SQL 2PC 100K–1M
  txn/s、并发重分片 34K–1.25M ops/s、亲和/选主 1–10M ops/s。
- 后续：SQL 2PC 真实协调器端到端、控制台 UI/商业化、选主与 Raft term
  联动（Phase 33+）。

## Phase 33 — SaaS 商业化与自治运维 ✅

- 交付：SQL 写 2PC 真实协调器（ADR-0144）、选主与 Raft term 联动
  （ADR-0145）、控制台 UI 原型 + SaaS 商业化（ADR-0146）、AI 容量规划
  （ADR-0147）、数据网格联邦查询（ADR-0148）、全球流量治理 + v1.6
  冻结（ADR-0149）。
- 测试：新增 319 项（surefire 口径）；全量回归 **4570/4570 全绿**
  （目标 ≥4450 ✅，+6 门控跳过）。
- 基准（[phase33-production-report.md](
  docs/benchmark/phase33-production-report.md)）：SQL 2PC 真实协调器
  694–3333 txn/s（决策日志落盘）、Raft term 选主 1–10M ops/s、
  联邦查询 45K–1.11M ops/s、流量治理 250K–3.33M ops/s。
- 后续：跨地域真实基准与 Linux Runner 执行（TD-048/049、BM-001/002）、
  控制台 SaaS 产品化、AI 容量自治闭环（Phase 34+）。

## Phase 34 — SaaS 产品化与自治运维闭环 ✅

- 交付：控制台 SaaS 产品化（ADR-0150）、AI 自治闭环（ADR-0151）、
  跨云联邦 + 数据主权（ADR-0152）、合规自动化（ADR-0153）、可观测性
  （ADR-0154）、商业化运营指标（ADR-0155）、v1.7 冻结与真实执行门禁
  （ADR-0156）。
- 测试：新增 356 项（surefire 口径）；全量回归 **4926/4926 全绿**
  （目标 ≥4890 ✅，+6 门控跳过）。
- 基准（[phase34-production-report.md](
  docs/benchmark/phase34-production-report.md)）：SaaS 控制台
  666K–3.33M ops/s、自治容量 250K–3.33M ops/s、跨云联邦
  125K–666K ops/s、追踪 30K–178K spans/s。
- 后续：跨地域真实门禁执行、全球多活 AI 全自治、跨云实时物化视图
  （Phase 35+）。

## Phase 35 — 全球 AI 自治与合规即代码 ✅

- 交付：全球受限自治（ADR-0157）、跨云物化视图（ADR-0158）、合规即代码
  （ADR-0159）、成本优化引擎（ADR-0160）、多租户网络隔离（ADR-0161）、
  SLA/SLO 管理（ADR-0162）、v1.8 冻结与门禁收敛（ADR-0163）。
- 测试：新增 360 项（surefire 口径）；全量回归 **5286/5286 全绿**
  （目标 ≥5286 ✅，+6 门控跳过）。
- 基准（[phase35-production-report.md](
  docs/benchmark/phase35-production-report.md)）：全球自治 165K–200K
  ops/s、物化视图 100K–476K ops/s、合规流水线 20K–200K runs/s、
  网络隔离 1M–2.5M checks/s。
- 后续：真实执行门禁收敛、全球自治自学习围栏、物化视图 CDC 增量刷新
  （Phase 36+）。

## Phase 36 — 门禁收敛与自学习自治 ✅

- 交付：真实执行门禁收敛 v2（ADR-0164）、自学习围栏（ADR-0165）、
  CDC 增量物化（ADR-0166）、合规持续证明（ADR-0167）、多云成本调度
  （ADR-0168）、网络策略即代码（ADR-0169）、SLO 预算容量 + v1.9 冻结
  （ADR-0170）。
- 测试：新增 374 项（surefire 口径）；全量回归 **5660/5660 全绿**
  （目标 ≥5656 ✅，+6 门控跳过）。
- 基准（[phase36-production-report.md](
  docs/benchmark/phase36-production-report.md)）：自学习围栏
  43K–3.33M ops/s、CDC 物化 100K–769K ops/s、证明链 17.9K–128K
  ops/s、多云调度 333K–10M ops/s、策略编译 143K–1M rules/s。
- 后续：真实执行门禁 Linux Runner、自学习多目标优化、CDC 增量状态
  持久化（Phase 37+）。

## Phase 37 — 多目标自治与跨云物化 ✅

- 交付：门禁收敛 v3（ADR-0171）、多目标围栏（ADR-0172）、跨云远端物化
  （ADR-0173）、第三方证明（ADR-0174）、Spot 竞价（ADR-0175）、策略
  审计（ADR-0176）、多 SLO 谈判 + v2.0 GA（ADR-0177）。
- 测试：新增 380 项（surefire 口径）；全量回归 **6040/6040 全绿**
  （目标 ≥6040 ✅，+6 门控跳过）。
- 基准（[phase37-production-report.md](
  docs/benchmark/phase37-production-report.md)）：多目标围栏
  125K–10M ops/s、远端物化 111K–714K ops/s、spot 调度 1M–10M
  ops/s、策略审计 30K–357K rules/s。
- 后续：真实执行门禁 Linux Runner、远端物化增量持久化、spot 实时市场
  数据（Phase 38+）。

## Phase 38 — 生产收敛与自治智能 ✅

- 交付：门禁收敛 v4（ADR-0178）、远端状态持久化（ADR-0179，TD-064
  关闭）、强化学习自治（ADR-0180）、物化视图生命周期（ADR-0181）、
  签名证明（ADR-0182）、Spot 中断迁移（ADR-0183）、风险评分 + v2.1
  （ADR-0184）。
- 测试：新增 393 项（surefire 口径）；全量回归 **6433/6433 全绿**
  （目标 ≥6430 ✅，+6 门控跳过）。
- 基准（[phase38-production-report.md](
  docs/benchmark/phase38-production-report.md)）：强化学习 1M–10M
  ops/s、状态落盘 2.7K–3.8K ops/s、签名 13.9K–172K ops/s、spot 迁移
  167K–909K ops/s。
- 后续：真实执行门禁 Linux Runner、多智能体联合学习、签名密钥轮换
  （Phase 39+）。

## Phase 39 — 多智能体自治与生产验证 ✅

- 交付：门禁收敛 v5（ADR-0185）、多智能体自治（ADR-0186）、自动分层
  （ADR-0187）、链上锚定（ADR-0188）、Spot 市场预测（ADR-0189）、
  自适应加固（ADR-0190）、Pareto 容量 + v2.2（ADR-0191）。
- 测试：新增 445 项（surefire 口径）；全量回归 **6878/6878 全绿**
  （目标 ≥6833 ✅，+6 门控跳过）。
- 基准（[phase39-production-report.md](
  docs/benchmark/phase39-production-report.md)）：多智能体 250K–2.5M
  ops/s、锚定 62.5K–178.6K ops/s、分层 1M–10M ops/s、spot 预测
  1M–5M ops/s。
- 后续：真实执行门禁 Linux Runner、异步拓扑感知聚合、真实市场 API
  （Phase 40+）。

## 技术债登记

| 编号 | 描述 | 来源 | 计划消除 |
| --- | --- | --- | --- |
| TD-001 | 单 Maven 模块；若模块耦合升高需评估拆分多模块 | ADR-0001 | Phase 7 前评估 |
| TD-002 | JDK 17 目标下暂不采用虚拟线程 | ADR-0003 | Phase 7 评估升级 JDK 21 |
| TD-005 | ARC 容量单位当前为 entry count，需改为 byte 口径 | ADR-0012 | Phase 9 出 ADR |
| TD-006 | LFU 索引更新为全局同步段；演进 Segment LFU + Async Buffer | ADR-0010 | Phase 7 优化 |
| TD-007 | WAL 恢复单线程（1M ≈ 1s，可接受） | ADR-0016 | Phase 7 评估 parallel replay |
| TD-008 | Checkpoint 全量快照；演进为 SSTable + Manifest | ADR-0016 | Phase 5 自然解决 |
| TD-009 | 随机 GET 基准受 OS page cache 影响；需 cold-cache 基准 | ADR-0018 | Phase 9 补测 |
| TD-010 | pending 迁移缓冲未持久化；需 Migration WAL / Pending Manifest | ADR-0017 | Phase 6 解决 |
| TD-011 | Flush 为手动触发；需 memory watermark + FlushScheduler | ADR-0017 | Phase 6 解决 |
| TD-012 | size-tiered 全量合并读放大；评估 leveled compaction | ADR-0019 | Phase 7 评估 |
| TD-013 | 快照式 Flush → Active/Immutable MemTable 轮转 | ADR-0020 | Phase 7 评估 |
| TD-014 | 迁移队列准入控制 / 批量 / worker 动态扩缩容 | ADR-0020 | Phase 7/9 评估 |
| TD-015 | 全量无锁读（ABA/回收/可见性）→ 暂缓 | ADR-0024 | 验证后新 ADR |
| TD-016 | Phase 9 三级基准：A 内存 / B 服务端 / C 生产全链路 | ADR-0023 | Phase 9 |
| TD-017 | 动态重分片（在线扩容） | ADR-0023 | Phase 10 |
| TD-018 | Hot Cache version check（当前 TTL 兜底） | ADR-0025 | Phase 10 评估 |
| TD-019 | 生产容量模型（吞吐/延迟/内存/磁盘），替代 IO 微优化 | ADR-0026 | Phase 9 |
| TD-020 | request→response 对象数优化（Future/Lambda/Callback 复用 + 批量写） | ADR-0023 | Phase 10 |
| TD-021 | Phase 10 以 JFR allocation / GC 对比为优化验收指标 | ADR-0029 | Phase 10 |
| TD-022 | Raft 日志内存态 → 文件分段 RaftLog + 快照 | ADR-0037 | ✅ 已关闭（Phase 12） |
| TD-023 | 进程内直调 → Netty TCP RPC + 超时重试 | ADR-0037 | ✅ 已关闭（Phase 12） |
| TD-024 | 复制滞后 13–35ms → CommitNotifier 立即补发（<1ms） | ADR-0037 | ✅ 已关闭（Phase 12） |
| TD-025 | 动态分片（slot 迁移）→ 在线迁移 + checkpoint | ADR-0035 | ✅ 已关闭（Phase 12） |
| TD-026 | 复制为同步串行 propose → 批量/流水线（9.2K ops/s） | ADR-0037 | ✅ 已关闭（Phase 13） |
| TD-027 | 迁移每批重建源快照 → 游标单次扫描 | ADR-0043 | ✅ 已关闭（Phase 13） |
| TD-028 | RPC 无 TLS/认证/限流 → 安全 RPC 层 | ADR-0041 | ✅ 已关闭（Phase 13） |
| TD-029 | 元数据单机 → Raft 元数据组 | ADR-0036 | ✅ 已关闭（Phase 13） |
| TD-030 | 小负载迁移（100B）受单条 put 成本限制 → 零拷贝批量写 | ADR-0059 | ✅ 已关闭（Phase 16，100B 82.7MB/s） |
| TD-031 | 复制 P50≈6ms（flush 周期 + 同步写者）→ 自适应 flush/异步客户端 | ADR-0044 | ✅ 已关闭（Phase 15） |
| TD-032 | RPC 静态 token → HMAC 签名 + 密钥轮换；mTLS | ADR-0046 | Phase 14 |
| TD-033 | 100B 迁移 18.3→59.8→82.7MB/s（目标 >100） | ADR-0059 | Phase 17 并行迁移（未达，如实记录） |
| TD-034 | Raft 同步等待 37~68K ops/s → 异步提案 129K ops/s | ADR-0054 | ✅ 已关闭（Phase 15） |
| TD-035 | 真实跨机部署 + tc netem 混沌 | ADR-0053 | Phase 16 部署产物交付；容器执行待 Linux+Docker |
| TD-036 | leader 转移仅元数据，未触发真实 Raft 交接 | ADR-0060 | ✅ 已关闭（Phase 17） |
| TD-037 | Region split/merge 未与存储数据搬迁联动 | ADR-0057 | Phase 18（独立 Raft 组搬迁） |
| TD-038 | 网关 CLUSTER 命令子集 | ADR-0061 | Phase 19（全字段 NODES/ASK 搬迁） |
| TD-039 | Region 键范围与 slot 区间路由未统一 | ADR-0057 | ✅ 已关闭（Phase 18，RoutingTable 统一） |
| TD-040 | 跨机容器混沌未执行（环境限制） | ADR-0069 | ✅ 已关闭（Phase 21，Docker 三节点 netem/分区/kill -9 执行） |
| TD-041 | MVCC GC 19–29MB/s（目标 >100）→ 批量删除路径 | ✅ 已关闭（Phase 20，107–285MB/s） |
| TD-042 | Redis 网关未接 MVCC 自动事务化 | ✅ 已关闭（Phase 20，GET/SET/DEL/MGET/MSET） |
| TD-043 | 事务/MVCC 未接入 Multi-Raft Region 网络路径，跨机事务验证受限 | ADR-0082 | ✅ 已关闭（Phase 21 TCP 事务协议 + Phase 23 运行时 + Phase 24 CI 容器 E2E 交付） |
| TD-044 | 跨机 disk slow / disk full 混沌未执行 | ADR-0086 | 部分关闭（Phase 22 in-JVM 语义覆盖；真实注入受 Docker 权限限制） |
| TD-045 | Phase 22 新增测试数低于 220 目标 | Phase 22 | ✅ 已关闭（Phase 23 补齐，全量 2007） |
| TD-046 | 真实容器 disk full / readonly / slow io 注入未完成（Docker Desktop 权限） | ADR-0090 | Phase 23（privileged/device-mapper 环境） |
| TD-047 | Metadata 单节点决策（无独立 Raft 组） | ADR-0095 | ✅ 已关闭（Phase 24 架构关闭：TxnMetadataNode + Raft 快照 + decisionIndex；网络化传输登记 TD-050） |
| TD-048 | compose.transaction 已提供，真实容器编排运行未执行 | ADR-0093 | Phase 25 交付物完成（故障注入脚本 + CI job）；真实 Runner 执行待触发 |
| TD-049 | 真实容器 disk 注入仍受限（fallocate/mount/fio） | ADR-0094 | Phase 25 交付物完成（block-device-chaos.sh + 门控测试）；Linux Runner 执行待触发 |
| TD-050 | 元数据 Multi-Raft 为进程内传输，网络化待跨机验证 | ADR-0095 | ✅ 已关闭（Phase 25：Netty RPC 三节点组 + 持久化日志/快照） |
