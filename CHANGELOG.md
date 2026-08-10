# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `.codex/` 工程控制中心：MASTER_PROMPT / DEVELOPMENT_RULES / AGENT_CONTEXT /
  CODE_REVIEW_RULES / RELEASE_RULES / tasks（phase0–phase4）。
- docs 知识库：requirements/acceptance、architecture（overview + storage +
  network + concurrency）、design（protocol/memory/lsm/bitcask/eviction）、
  benchmark（计划 + 报告占位）、review、operations。
- src/main 模块骨架（network/protocol/command/storage/cache/scheduler/
  memorypool/metrics/config）、tests（unit/integration/stress/chaos）、
  benchmarks（throughput/latency/memory/migration）。
- 工程设施：scripts（build/benchmark/stress-test/release）、config
  （tiering-kv.yaml/benchmark.yaml）、examples、tools、.github/workflows
  （build/test/benchmark）。
- 根级文档：CONTRIBUTING.md、LICENSE（待定占位）。
- ADR-0004（缓存策略）、ADR-0005（持久化格式）。
- Phase 1 RESP 协议：RESP2 编解码（RespValue / RespDecoder / RespEncoder）、
  增量解析、pipeline、inline 命令、协议错误处理与防注入编码。
- Phase 1 命令层：PING / ECHO / SET / GET / DEL / EXISTS、命令注册表、
  KVStore 接口 + InMemoryKVStore。
- Phase 1 网络层：Netty TCP 服务端（TieringKvServer / ConnectionInitializer /
  CommandHandler）与入口 Main。
- Phase 1 测试：协议/命令单元测试、TCP 集成测试（pipeline / inline / 并发 /
  二进制安全）、延迟冒烟基准。
- ADR-0006（RESP2）；依赖：Netty 4.1.115、AssertJ 3.26.3、exec 插件。
- Phase 2 内存引擎：StorageEngine SPI、64 段 SkipList MemTable、KeyValueEntry
  （版本 / tombstone / TTL / size）、跨段归并有序迭代器、MemoryManager
  （配额 + 淘汰回调接口）、TTLManager（惰性 + 主动混合过期）。
- `SET key value EX seconds | PX milliseconds` 支持。
- 存储测试套件（MemTable / StorageEngine / Delete / TTL / Iterator / 并发 /
  MemoryManager）与内存引擎基准（GET 10K/100K/1M，并发写 10/50/100 线程）。
- ADR-0007（SkipList）、ADR-0008（分段锁）、ADR-0009（TTL 混合策略）。
- Phase 3 热数据管理层：HotnessTracker / FrequencyCounter（LFU + 周期衰减）、
  LFUPolicy（快照索引 O(logN)）、ARCPolicy（T1/T2/B1/B2 + p 自适应原型）、
  EvictionManager、MigrationCallback、TrackingStorageEngine。
- 超内存配额触发淘汰：候选选择 → 迁移回调 → 物理移除；用户 DEL 保持 tombstone。
- 缓存测试套件（热度 / LFU / 衰减 / ARC / 淘汰 / 迁移 / 集成）与缓存基准。
- ADR-0010（热度跟踪）、ADR-0011（LFU 衰减）、ADR-0012（ARC 评估）。
- 迁移接口升级：`MigrationCallback` → `TierMigration`（SUCCESS / FAILED /
  RETRY），淘汰遵循"先迁移、后删除"（ADR-0013）。
- Phase 4 WAL 持久化层：WALManager / WALWriter / WALReader / LogSegment /
  SegmentManager / ChecksumValidator（CRC32C）/ RecoveryManager /
  CheckpointManager / WALStorageEngine。
- 写路径接入：WAL append（默认 EVERY_SEC）→ MemTable → ack；淘汰删除也落
  DELETE 记录；TTL 过期不落盘（由 PUT 推导）。
- 崩溃恢复：校验 → 重放 → 截断残尾；checkpoint（快照 + offset）加速；
  宕机期间过期的键不复活。
- WAL 测试套件（条目/校验/读写/轮转/恢复/崩溃三用例/检查点/集成）与
  WAL 基准（append 100K/1M、恢复 100K/1M）。
- ADR-0014（写策略）、ADR-0015（记录格式）、ADR-0016（崩溃恢复）。
- 基准口径修正：WAL append 指标标注为 buffered mode（非逐条 fsync），
  不等同 durable write throughput（Phase 4 评审）；技术债登记 TD-007/008。
- Phase 5 冷存储引擎：ColdStorageEngine（pending + 多表 + Manifest）、
  SSTableWriter / SSTableReader / Block / BlockIndex / BloomFilter /
  DiskIterator / CompactionManager / CompactionTask / FlushManager /
  ColdMigration。
- MemTable Flush（版本守卫）+ WAL checkpoint 接入；淘汰迁移写入冷层
  （pending → SSTable）。
- 冷存储测试套件（Bloom/SSTable/Flush/Compaction/Engine/迁移集成）与
  冷存储基准（写 100K/1M、随机 GET、FPR、合并）。
- ADR-0017（冷层策略）、ADR-0018（SSTable 格式）、ADR-0019（合并策略）。
- Phase 5 评审处置：SSTable 写吞吐改为 Peak/Average 双口径；登记 page cache
  影响与 cold-cache 基准计划（TD-009）；技术债 TD-010（pending 持久化）、
  TD-011（自动 Flush）、TD-012（leveled compaction）。
- Phase 6 自动调度：TieringController / WatermarkManager（70/85/95）/
  FlushScheduler（异步 + 去重 + 失败保留）/ MigrationScheduler +
  MigrationLog（持久化 + 启动恢复 + 幂等重放）/ BackPressureController /
  TierWorkerPool / StorageMetrics / TieringStorageEngine。
- EvictionManager 异步化：候选入队 → worker 写冷层 → WAL DELETE → 删内存；
  重试上限后 FAILED（内存保留）；CRITICAL 限写返回 -ERR。
- tiering 测试套件（水位/Flush/迁移队列/恢复/背压/控制器/集成）与
  tiering 基准（Flush、迁移 100K/1M、内存压力）。
- ADR-0020（调度模型）、ADR-0021（水位策略）、ADR-0022（迁移持久化）。
- Phase 6 评审处置：定位升级为「Redis 兼容 LSM 分层 KV 存储引擎」；登记
  TD-013（Immutable MemTable 轮转）、TD-014（迁移队列准入/批量/扩缩容）。
- Phase 7 并发优化：KeyShardExecutor / ShardRouter / ShardQueue / ShardWorker /
  ExecutionContext / ConcurrencyMetrics；CommandEngine.executeAsync +
  ResponseSequencer（RESP 保序）。
- MemTable 64 → 256 段；HotKeyDetector / AccessCounter / HotKeyPolicy /
  HotKeyReadCache / RequestCoalescer / HotKeyStorageEngine。
- 并发测试套件（路由/顺序/并行/读写/热点/合并/压力/异步保序）与并发基准
  （GET/SET/Mixed、热点 90%、分片对比）。
- ADR-0023（分片执行）、ADR-0024（MemTable 并发）、ADR-0025（热点缓解）。
- Phase 7 评审处置：定位升级为「高并发 Redis 协议兼容 LSM 冷热分层 KV
  存储引擎」；登记 TD-015（无锁读暂缓）、TD-016（Phase 9 三级基准）、
  TD-017（动态重分片）、TD-018（Hot Cache version check）。
- Phase 8 评审处置：能力矩阵 21 项确认；登记 TD-019（Phase 9 生产容量模型，
  停止 IO 微优化）；cold-cache 冷启动基准保持 TD-009。
- Phase 9 生产基准：三级基准套件（A/B/C）+ 管道 RESP 客户端；5 份报告
  （memory/server/production/capacity-model/deployment-profile）。
- 结论：A 级 GET 4.7M / SET 4.4M ops/s；B 级 pipeline64 峰值 218–231K
  （500K 目标未达，瓶颈=协议/调度）；C 级全链路 115–178K ops/s；
  Workload D 压力下内存受控、冷层落盘。
- ADR-0029（基准方法）、ADR-0030（容量模型）、ADR-0031（部署画像）。
- Phase 10 高级优化与生产化：响应批处理（ResponseBatcher/ResponseBuffer，
  batch=64 + 排空 flush）、回调式执行（去掉每请求 Future）、YAML 配置
  （TieringConfig + application.yaml）、MetricsRegistry + INFO 命令、
  ShutdownManager 优雅停机（stop accept → drain → WAL force + checkpoint）。
- 基准：Level B pipeline64×500 218–231K → 465K（>400K ✅）、pipeline128
  → 1.14M；Level C 154–326K 无回退。
- ADR-0032（响应批处理）、ADR-0033（内存模型）、ADR-0034（服务生命周期）。
- Phase 10 最终评审：确认 10 阶段路线图全部完成；定位为完整冷热分层存储
  系统（14 模块能力矩阵全 ✅）；最终评审归档 architecture-review。
- Phase 11 分布式集群：16384 hash slot 路由（HashSlotRouter /
  SlotTable / ShardGroup / ShardId / PartitionKey，CRC16/CCITT）、
  元数据服务（MetadataServer / ClusterMetadata / NodeRegistry /
  ShardRegistry / TopologyManager）、最小真实 Raft（RaftNode / RaftState /
  LogEntry / Term / VoteRequest/Response / AppendEntriesRequest/Response /
  LeaderElection / ReplicationManager：选举 + 心跳 + 日志复制 + commit/
  apply）、ReplicatedStorageEngine 复制适配器（写经 Raft 复制后 apply
  本地引擎，不改 MemTable/WAL/SSTable）、ClusterNode / ClusterClient。
- Phase 11 故障转移：leader 崩溃 → 新 leader 选举（≤310ms）；replica
  崩溃 → 半数存活继续服务；无多数派不提交；旧 leader 回归安全降级。
- Phase 11 测试：ShardingTest（10）/ MetadataTest（10）/ RaftTest（21）/
  FailoverTest（9）/ ClusterIntegrationTest（1，3 节点 SET → 复制 →
  杀 leader → 选举 → GET 正确），共 51 项新测试；全量回归 288 项全绿。
- Phase 11 基准（docs/benchmark/cluster-report.md）：单分片复制写
  154K ops/s（P99=0.027ms）、读 750K ops/s（P99=4μs）、路由开销
  ~23–36ns/op、复制滞后 ≤35ms、选举 124–310ms（目标 <5s ✅）。
- ADR-0035（哈希槽）、ADR-0036（Raft 元数据）、ADR-0037（Raft 复制）、
  ADR-0038（心跳 + 随机选举超时）。
- Phase 11 评审处置：登记 TD-022（Raft 日志持久化）、TD-023（TCP RPC
  传输）、TD-024（提交后立即补发 commitIndex 降低复制滞后）、TD-025
  （动态 slot 迁移）；技术债清单见 ROADMAP。
- Phase 11 外部评审归档（docs/review/phase11-cluster-review.md）：确认
  哈希槽 / 元数据 / Raft 修复 / 复制适配器设计正确；四项不足（Raft Log
  不持久化、Snapshot 缺失、进程内 RPC、Slot 迁移缺失）对应 TD-022/
  023/025 进入 Phase 12；元数据 Raft Cluster（etcd/PD 方向）纳入
  Phase 12 计划。
- Phase 12 分布式生产化：
  - RaftLog（FileRaftLog / LogSegment / RaftLogWriter / RaftLogReader /
    RaftLogRecovery：MAGIC/VERSION/TERM/INDEX/COMMAND_TYPE/DATA/CRC32C，
    SYNC/ASYNC/NONE，段滚动 + 尾部截断恢复）+ RaftPersistentState
    （term/votedFor/commitIndex 落盘，term 变更 force、commit 缓冲）；
  - Snapshot（SnapshotManager / SnapshotWriter / SnapshotReader /
    SnapshotMetadata：快照创建/加载/校验，RaftNode 超阈值自动压缩 +
    InstallSnapshot 追赶 + 重启"快照恢复 + 剩余日志重放"）；
  - Netty RPC（RpcServer / RpcClient / RpcCodec / RequestId /
    RaftMessageCodec：长度前缀帧 + 请求关联 + 超时 + 幂等重试 +
    连接复用；三类 Raft 消息）+ RaftTransport 抽象
    （LocalRaftTransport 测试回退 / NettyRaftTransport 生产）；
  - 复制优化（CommitNotifier 立即补发 commitIndex + ReplicationTracker /
    FollowerProgress：复制滞后 13–35ms → <1ms，目标 <5ms ✅）；
  - Slot 在线迁移（SlotMigrationManager / MigrationTask /
    MigrationCheckpoint / MigrationState：INIT→COPYING→VERIFYING→
    SWITCHING→DONE，checkpoint 持久化续传 + 源/目标 CRC 校验 + SlotTable
    原子切换 + 切换后清理源）；
  - StorageSnapshotCodec（存储引擎状态机快照编解码）。
- Phase 12 测试：RaftLog 21 / Snapshot 12 / RPC 19 / 迁移 11 /
  复制优化 5 / 快照集成 2 / TCP 集群集成 3（3 节点真实 TCP：
  SET→复制→杀 leader→选举→GET；重启恢复 term/commitIndex/数据；
  复制滞后 <100ms 断言）——新增约 73 项；全量回归最终统计见评审报告。
- Phase 12 基准（docs/benchmark/distributed-production-report.md）：
  RaftLog ASYNC append 102K ops/s（P99=27μs）、TCP 提交 1,359 ops/s
  （P50=0.65ms / P99=2.16ms）、复制滞后 0ms、RPC 9.3K ops/s
  （P50=100μs，单连接复用）、迁移 16.1MB/s + 断点续传 549ms/90K。
- ADR-0039（RaftLog 格式）、ADR-0040（快照策略）、ADR-0041（RPC 设计）、
  ADR-0042（复制滞后优化）、ADR-0043（Slot 迁移策略）。
- Phase 12 评审处置：TD-022~025 关闭；登记 TD-026（批量/并行复制）、
  TD-027（迁移单次迭代游标）、TD-028（RPC TLS/认证）、TD-029
  （元数据 Raft 化落地）。
- Phase 13 分布式优化：
  - Raft 批量/流水线复制：RaftReplicationConfig（maxBatchEntries /
    maxBatchBytes / flushInterval / maxInflight），异步批量发送 +
    group commit + inflight/lastSent 跟踪；日志镜像缓存消除持锁文件读；
  - 游标迁移：MigrationCursor（lastKey/lastVersion/checkpointOffset）、
    PAUSED 状态、`slot-{start}.cursor` CRC 游标文件、pause/resume/recover；
  - 安全 RPC：RpcSecurityConfig（TLS PEM 证书）+ RpcAuthInterceptor
    （Token 认证/过期 + AUTH/ERROR 帧）+ TokenBucket 限流（ERR RATE_LIMIT）；
    RpcClient 连接/重试全程非阻塞（修复事件循环阻塞导致的提交停滞）；
  - 元数据 Raft 化：MetadataRaftGroup / MetadataCodec / MetadataState
    （每副本独立状态机，修复共享状态交错问题）/ MetadataClient；
  - 跨机部署文档（docs/deployment/distributed-deployment.md）。
- Phase 13 测试：批量复制 15 / 迁移游标 15 / RPC 安全 19 / 元数据 Raft
  24 / 集成 5 / 基准 4 —— 新增 82 项；全量回归最终统计见评审报告。
- Phase 13 基准（docs/benchmark/phase13-report.md）：复制吞吐 9,220
  → 22,169 ops/s（目标 >5000 ✅）、迁移 216–245MB/s @1KB
  （目标 >100MB/s ✅）、RPC 安全开销 +50–70%、元数据故障转移
  115–290ms。
- ADR-0044（批量复制）、ADR-0045（游标迁移）、ADR-0046（RPC 安全）、
  ADR-0047（元数据 Raft）。
- Phase 13 评审处置：TD-026~029 关闭；登记 TD-030（MemTable 批量写）、
  TD-031（自适应 flush/异步客户端）、TD-032（token 签名轮换/mTLS）。
- Phase 14 生产加固：MemTable.applyBatch（批量写 + WAL 批量追加）、
  AdaptiveFlushController、ReplicationController + putAsync、HMAC-SHA256
  认证（防重放/轮换）+ mTLS、元数据 Raft 持久化（FileRaftLog +
  MetadataSnapshot）、跨机部署指南 + 故障注入测试（5/5）。
- Phase 14 测试：新增 101 项；基准：100B 迁移 18.3MB/s 与 Raft 37.3K
  ops/s 未达目标（已如实记录，TD-033/034），HMAC 开销≈0、元数据重启
  194ms。
- Phase 15 生产验证：
  - 流式迁移（ADR-0053）：StreamingMigrator 单次快照 + 跨批次持久
    scanner + MigrationStreamCursor（CRC/原地更新）+ 版本屏障 + 动态
    batch（100B→4096 / 1KB→1024 / 10KB→256）；修复每批重建 O(N)
    快照的隐藏 O(N²) 行为，100B 迁移 2.9 → 59.8 MB/s；
  - 全异步提案（ADR-0054）：RaftNode.proposeBatch（N 请求 → 单次
    AppendEntries）+ AsyncReplicationClient（有界队列 NORMAL/WARNING/
    CRITICAL 背压 + 内联批量 drain + leader 变更整批重试 ≤3）；
  - 证书生命周期（ADR-0055）：CertificateManager（加载/校验/过期/
    reload/原子轮换 + server/client supplier）+ CertificateWatcher
    （WatchService 监听 .crt/.key 变更）；
  - 混沌验证（ADR-0053~0056 支撑）：ChaosValidationTest 16 项（100ms
    延迟/5%/10% 丢包/follower/leader 分区/磁盘慢/leader 击杀/replica
    重启追平/混合故障/法定人数丢失），三轮稳定；发现并修复 Raft 缺陷
    ——冲突截断的未提交提案被同 index 新条目虚假完成
    （RaftNode.failPendingFromLocked + 回归测试）；
  - 集群可观测性（ADR-0056）：ClusterMetricsRegistry（raft_proposal_qps/
    raft_commit_latency/raft_replication_lag/migration_speed/
    migration_cursor/migration_remaining/certificate_expire_time）+
    ClusterInfo + `INFO CLUSTER`（node/role/term/leader/slot）；
  - INFO 命令扩展支持 `INFO [section]`（未知 section 返回错误）；
  - 测试：新增 98 项（迁移 19 / 异步 Raft 21 / 证书 15 / 混沌 16+1 /
    可观测性 15 / 基准 11）；全量回归 650 项全绿（Phase 1–15）；
  - 基准（docs/benchmark/phase15-production-validation-report.md）：
    迁移 100B 59.8 / 1KB 173.3 / 10KB 589.8 MB/s；Raft 1/64/256 写者
    129/259/331K ops/s（目标 100K/200K ✅），P99 0.009/3.071/9.824ms；
    混沌选举恢复 p50 155ms（目标 <5s ✅）；TLS 轮换 p50 13.5ms；
  - 未达标（如实记录）：100B/1KB 迁移未达 >100/>300 MB/s，瓶颈 = 写
    路径每条目 3 次数组拷贝（Mutation 构造/访问器 + KeyValueEntry），
    零拷贝批量写路径列入 Phase 16（TD-033）。
- Phase 16 Multi-Raft 架构演进：
  - Region 模型（ADR-0057）：Region（[startKey,endKey) + leader/peers +
    epoch + state）、RegionEpoch（confVer/version 从 1 起）、RegionManager
    （TreeMap 路由 + create/split/merge + tombstone 审计 + epoch guard +
    transferLeader）、StaleRegionEpochException；
  - Multi-Raft（ADR-0058）：MultiRaftNode（多 Raft 宿主）、RaftGroupManager
    （按 Region 创建/销毁组，含持久化组）、MultiRaftEndpoint（单端口共享
    RPC 端点 + [groupId] 前缀路由）、MultiRaftTransport（RaftTransport
    兼容，RaftNode API 零改动）；
  - 零拷贝批量写（ADR-0059）：RawMutation（所有权转移，不克隆）、
    KeyValueEntry record→class + owned 构造、MemTable.applyRawBatch
    （平面桶分组 + 单段单锁 + 版本按序分配）、SkipList.putAndGetOld
    （单次查找）、StreamingMigrator 切换零拷贝路径、全槽位迁移跳过
    slot 哈希、游标 advance 去克隆；
  - 放置控制（ADR-0060）：PlacementManager（distribution/balanceSkew/
    isBalanced/transferLeader），自动 rebalance 暂缓；
  - Region 可观测性：RegionMetricsRegistry（region_count/region_size/
    region_split_count/raft_group_count/leader_distribution/
    region_move_bytes）+ RegionInfo + `INFO REGIONS`；
  - 混沌验证：ChaosClusterTest 20 项（多 Region 延迟/丢包/分区/磁盘慢/
    双组击杀/重启追平/混合故障/epoch 保护）；
  - 缺陷修复：新 leader 以非空日志当选后不回填滞后 follower
    （心跳拒绝未回退 nextIndex → 修复 + 回归测试
    newLeaderBackfillsLaggingFollowerWithoutNewWrites）；
  - 跨机部署：ClusterMain（3 JVM 拓扑入口）+ deploy/Dockerfile +
    docker-compose.yml + chaos-netem.sh + 跨机指南；
  - 测试：新增 138 项（Region 34 / Multi-Raft 32 / Zero-Copy 21 /
    Chaos 21 / Placement+可观测性 23 / 基准 6）；
  - 全量回归 788/788 全绿（Phase 1–16）；
  - 基准（docs/benchmark/phase16-multiraft-report.md）：零拷贝迁移
    100B 82.7 / 1KB 223.1 / 10KB 631.0 MB/s；Multi-Raft 1/2/4 组
    110/222/404K ops/s（线性扩展 2.02×/3.68× ✅）；TCP 单端口多组
    P99 0.551ms；故障恢复 p50 183ms；
  - 未达标（如实记录）：100B/1KB 迁移未达 >100/>300 MB/s
    （82.7/223.1，剩余每条目固定开销 → Phase 17 并行迁移）。
- Phase 17 Region 生命周期与分布式存储完善：
  - Region Split（ADR-0061）：SplitController（PREPARE/SNAPSHOT/INSTALL/
    COMMIT/CLEANUP）+ SplitSnapshot（CRC/屏障）+ SplitWriteBuffer
    （窗口写不丢失）+ epoch+1 + 路由原子切换；
  - Region Merge（ADR-0062）：MergeController（PREPARE→LOCK→TRANSFER→
    UPDATE_META→TOMBSTONE），右→左零拷贝搬迁，失败状态可重置重试；
  - 并行迁移（ADR-0063）：RegionTransferManager + MigrationChunk +
    ChunkWorker + ChunkCheckpoint（CRC/retry/pause-resume）+
    MemTable.segmentIterator 按段分片；100B 209.1MB/s（>150 ✅）；
  - 真实 leader 交接（ADR-0064）：RaftNode.transferLeadership +
    TimeoutNow RPC（三类传输）+ receiveTimeoutNow 立即选举 +
    LeaderTransferManager；24ms（<500ms ✅）；
  - Redis Cluster Gateway：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS +
    MOVED slot host:port；GET 3.68M / SET 1.67M ops/s；
  - 自动均衡（ADR-0065）：BalanceScheduler 检测 region/leader/disk/cpu
    压力生成 BalancePlan（epoch 保护，不自动执行危险迁移）；
  - 可观测性：RaftMetricsRegistry + MigrationMetricsRegistry +
    RegionMetricsRegistry.recordMerge + INFO RAFT / INFO MIGRATION；
  - 混沌：RegionChaosTest（分裂 10000 并发写无丢失、合并故障恢复、
    200ms 延迟 + 10% 丢包交接、Region 隔离、旧纪元拒绝）；
  - 测试：新增 159 项；全量回归 947/947 全绿（目标 ≥900 ✅）；
  - 基准（docs/benchmark/phase17-region-report.md）：Split 1M≈0.9s、
    Merge 1M≈0.7s、并行迁移 209.1MB/s、Transfer 24ms、Gateway 全达标。
- Phase 18 分布式生产集成：
  - 统一路由（ADR-0066）：RoutingTable（键范围+slot 区间+epoch+leader+
    raftGroup）+ RoutingCache（陈旧自刷新）+ RouteEpochGuard +
    MOVED/ASK/TRYAGAIN 统一；
  - 真实 TCP 网关（ADR-0068）：NettyClusterGateway + 批量 flush
    （GET 719K / SET 590K ops/s）+ CLUSTER SLOTS/NODES；
  - Split/Merge 与 Raft 联动（ADR-0067）：RegionRaftMigrationManager
    （子/合并组 + 路由原子切换 + 回滚/恢复幂等）；
  - 生产化迁移：ByteRateLimiter + MigrationScheduler + 迁移指标
    （remaining/error）；
  - 跨节点部署（ADR-0069）：docker-compose.cluster.yml +
    CrossMachineChaosTest（20 项）；
  - 可观测性（ADR-0070）：MetricsExporter（Prometheus）+
    ProductionInfo（INFO CLUSTER 聚合）；
  - 测试：新增 165 项；全量回归最终统计见合并后报告（目标 >1100）；
  - 基准（docs/benchmark/phase18-production-report.md）：Gateway
    719K/590K ops/s、迁移 209.1/986.0 MB/s、Split/Merge 1M ~0.9/~0.7s。
- Phase 9 评审处置：确认瓶颈分层（A 4.7M → B 230K → C 150K，瓶颈=协议/调度）；
  登记 TD-020（request→response 对象数优化）与 TD-021（JFR 验收指标）。
- Phase 8 IO 优化：MmapSSTableReader（零拷贝块读）+ FileChannel baseline、
  MemoryPool（DirectBuffer 大小类池 / BufferArena / BufferRecycler /
  AllocationTracker）、BlockCache（LRU + 池化缓冲 + invalidate/clear）、
  IOStatistics；ColdStorageEngine 默认 mmap + cache。
- IO 测试套件（mmap/baseline/cache/pool/恢复）与 IO 基准（mmap vs
  FileChannel、缓存冷热混合、内存/GC 概况）。
- ADR-0026（SSTable IO 策略）、ADR-0027（Off-Heap 策略）、
  ADR-0028（Block Cache 策略）。

### Changed

- 目录结构与标准框架对齐；ADR-0002 更名为 ADR-0002-storage-engine.md；
  architecture.md 拆分为 overview + 三个分主题架构文档。
- src/main 骨架目录统一为 Maven 标准布局 `src/main/java/io/tieringkv`
  （TD-004 关闭）。
- 修复：Netty 管道顺序（Encoder 位于 pipeline 最前，符合出站事件方向）；
  ByteProcessor 扫描语义修正。
- 根据 Phase 1 评审修正定位措辞：README 明确为「RESP 兼容 KV Server 基础层」，
  性能与分层能力列为演进目标；评审意见归档至
  docs/review/architecture-review.md。
- Command 层迁移至 StorageEngine；移除 KVStore / InMemoryKVStore（Phase 1
  占位实现，由 MemTable 取代）。
- surefire 测试堆配置 `-Xmx1g`（支持 1M 数据集基准）。
- 基准报告标注口径：存储层 GET baseline（P99≈2.5μs）与网络端到端
  （P99≈0.19ms）分离，避免口径混淆（Phase 2 技术评审）。

## [0.1.0] - 2026-08-09

### Added

- 初始化 Git 仓库（main + develop 分支）与完整目录骨架。
- 新增 README.md、ROADMAP.md、CHANGELOG.md、.gitignore。
- 新增 Maven 构建骨架（Java 17、JUnit 5），`mvn test` 可验证。
- 新增 ADR-0001（项目总体架构）、ADR-0002（存储引擎策略）、ADR-0003（并发模型）。
