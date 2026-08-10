# Tiering-KV

> 高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎
> （RESP + WAL + MemTable + SSTable + 自动调度 + Key Sharding +
> Raft 持久化集群 + 批量复制 + 安全 RPC + 元数据 Raft + 游标迁移）。

**阶段状态：Phase 20（事务生产化与存储优化）✅（Phase 0–19 全部完成 ✅）**

## 项目定位

**当前定位**：高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎——已完成 RESP
协议、内存引擎、LFU/ARC 淘汰、WAL 持久化、SSTable 冷层、自动 Flush /
异步迁移 / 背压、Key Sharding 异步执行与热点治理（Phase 1–10），并完成
分布式集群基础：16384 hash slot 路由、元数据服务、最小真实 Raft（选举 /
心跳 / 日志复制 / 提交）与故障转移（Phase 11），以及分布式生产化：
Raft 日志持久化 + 快照、Netty TCP RPC、复制滞后优化（<1ms）、在线
Slot 迁移（Phase 12），以及分布式优化：批量/流水线复制（>5000 ops/s）、
游标迁移、TLS/认证/限流安全 RPC、元数据 Raft 化（Phase 13），以及生产
加固：MemTable 批量写、自适应 Flush/复制、异步提案、HMAC/mTLS、
元数据持久化与故障注入（Phase 14），以及生产验证：流式迁移（单次快照 +
游标 + 版本屏障）、全异步批量提案（129–331K ops/s）、证书生命周期自动
轮换、混沌验证（16 项，发现并修复 Raft 截断提案虚假完成缺陷）、集群
可观测性（INFO CLUSTER）（Phase 15）；面向 redis-cli 与主流客户端提供
PING / ECHO / SET / GET / DEL / EXISTS 能力；以及 Multi-Raft 架构演进：
Region 抽象（键范围 + epoch 路由保护）、每 Region 独立 Raft 组
（单端口共享传输 + 组隔离）、零拷贝批量写（RawMutation 所有权转移）、
放置控制（分布/均衡/leader 转移）、多 Region 混沌验证（发现并修复
滞后副本回填缺陷）（Phase 16）；以及 Region 生命周期闭环：自动分裂/
合并（写缓冲保证并发写不丢失）、并行迁移（100B 209MB/s）、真实 Raft
领导权交接（TimeoutNow，24ms）、Redis Cluster 网关（MOVED + CLUSTER
SLOTS）、自动均衡计划（epoch 保护）（Phase 17）；以及分布式生产集成：
统一 Region/Slot 单路由模型（epoch 守卫 + 缓存自刷新）、真实 TCP
Redis Cluster 网关（GET 719K / SET 590K ops/s）、Split/Merge 与 Raft
组联动（子组独立日志 + 回滚）、生产化迁移（限速 + 自适应调度 +
Prometheus 指标）、三节点容器部署产物与跨机混沌（Phase 18）。
以及数据库内核：MVCC 多版本模型 + HLC 时间戳 + Snapshot Read +
Percolator 2PC 事务（Prewrite/Commit/Rollback）+ 锁与冲突检测 +
事务恢复 + MVCC GC + 跨 Region 2PC + Raft 事务日志（Phase 19）。

**边界（如实声明）**：仍为教学/工程级实现，暂不宣称"高性能 Redis 替代品"；
分布式为真实 TCP + 持久化原型，基准以进程内为主，跨机 `tc netem` 验证
待 Linux+Docker 环境执行（部署产物已交付）；100B/1KB 零拷贝迁移
（82.7/223.1 MB/s → 并行 209.1/986.0 MB/s）已达标；网关 CLUSTER 命令
为子集；split/merge 与独立 Raft 组数据搬迁已联动；跨机容器混沌待
Linux+Docker 执行（产物已交付）；pub/sub、Lua、RESP3 与正式性能基线
（内存降低 60%–80%）为后续演进方向。

## 核心能力

1. Redis RESP 协议兼容
2. 内存 + 磁盘冷热分层存储
3. LFU / ARC 数据热度管理
4. 异步冷热迁移
5. LSM-Tree / Bitcask 持久化
6. 高并发网络模型
7. mmap 零拷贝优化
8. 分段锁 / 无锁数据结构
9. Bloom Filter 防缓存击穿
10. 自研 Memory Pool

## 总体架构

```text
Client
  │
  ▼
RESP Protocol
  │
  ▼
Network Layer
  │
  ▼
Command Engine
  │
  ▼
Memory Tier (MemTable)
  │
  ▼
Hotness Manager
  │
  ▼
Cold Storage
  │
  ▼
Bitcask / LSM Tree
```

横切模块：WAL、Scheduler（异步迁移）、Metrics、Eviction（LFU/ARC）、Compaction、
Bloom Filter、Memory Pool。

代码组织为 `io.tieringkv` 根包下的模块分包：`network`、`protocol`、`command`、
`storage`、`memory`、`cache`、`eviction`、`wal`、`sstable`、`compaction`、
`scheduler`、`metrics`、`benchmark`。跨层只允许依赖接口，禁止反向依赖
（见 [ADR-0001](docs/adr/ADR-0001-project-architecture.md)）。

## 内存引擎架构（Phase 2）

```text
Command Layer
     │
     ▼
StorageEngine（SPI）
     │
     ▼
MemTable（64 段 SkipList + 分段读写锁）
     ├── KeyValueEntry（版本 / tombstone / TTL / size）
     ├── MemoryManager（配额 + 淘汰回调接口）
     └── TTLManager（惰性 + 主动混合过期）
```

- 有序键空间与有序迭代 → 为 LSM / SSTable 生成准备（ADR-0007）；
- 64 段分段锁替代全局锁（ADR-0008）；
- DELETE 使用 tombstone；TTL 惰性 + 主动清扫（ADR-0009）；
- `SET key value EX seconds | PX milliseconds` 已支持。

## 热数据管理层（Phase 3）

```text
Command Layer
     │
     ▼
TrackingStorageEngine（装饰器：产生 AccessEvent）
     │
     ▼
EvictionManager
     ├── LFU（默认：频率 + 周期衰减）
     ├── ARC（原型：T1/T2 + B1/B2 ghost）
     └── MigrationCallback（Phase 4/6 接冷存储）
```

- 每次 GET / SET / DELETE 产生访问事件，热度数据驱动淘汰决策；
- LFU 频率按可配置周期衰减（×0.5，懒计算）；
- 超内存配额 → 选候选 → 迁移回调 → 物理移除；用户 DEL 仍走 tombstone。

## 持久化层（Phase 4，WAL）

```text
Command → WALStorageEngine
    ├── WALManager（append / flush / rotate / checkpoint）
    ├── RecoveryManager（启动恢复：校验 → 重放 → 截断残尾）
    └── MemTable
```

- 写路径：WAL append（默认 EVERY_SEC，缓冲模式，≤1s 丢失窗口）→ MemTable
  → ack；ALWAYS 提供逐条 fsync 强一致选项；
- 记录格式：MAGIC / VERSION / TYPE / 时间戳 / 长度 / TTL / 版本 + CRC32C
  （ADR-0015，禁用 Java 序列化）；
- segment 滚动（`wal/%06d.log`，64MB）+ checkpoint（快照 + offset）加速恢复；
- 恢复时按绝对过期点判定 TTL，宕机期间过期的键不复活。

## 冷存储架构（Phase 5，SSTable / LSM）

```text
WAL → MemTable（热层）→ Flush → SSTable（冷层）
    → Manifest + Compaction；读取：pending → 新表 → 旧表
```

- SSTable：Data Blocks（4KB，CRC32C）→ Index Block → Bloom Block → Footer；
- 随机读：Bloom → Index 二分 → Block 解码 → 块内二分；
- 淘汰迁移：EvictionManager → ColdMigration → pending 缓冲 → 阈值落 SSTable；
- 合并：size-tiered 触发 + 全量 latest-wins（重复键 / tombstone / 过期 TTL）。

## 自动调度架构（Phase 6）

```text
Command → TieringStorageEngine（背压 + 水位）
    → TieringController
        ├── WatermarkManager（70% / 85% / 95% + 队列阈值）
        ├── FlushScheduler → 后台 Flush Worker → SSTable
        ├── MigrationScheduler → MigrationLog → 后台 Worker → ColdStorage
        └── BackPressureController（CRITICAL 限写，超时 -ERR）
```

- 自动 Flush：写后水位检查触发，后台执行、去重、失败保留重试；
- 异步迁移：EvictionManager 入队 → worker 写冷层 → WAL DELETE → 删内存；
  状态持久化到 `migration/migration.log`，启动恢复未完成任务；
- 指标：StorageMetrics 覆盖内存 / 迁移 / Flush / 冷层。

## 并发架构（Phase 7）

```text
Netty EventLoop → CommandEngine.executeAsync → KeyShardExecutor
    → ShardRouter（fnv1a % N）→ ShardQueue → ShardWorker → StorageEngine
    → ResponseSequencer（每连接按序号释放响应）
```

- 同键 FIFO 有序、异键并行；RESP 响应顺序不被并行破坏；
- MemTable 256 段分段锁；热点读走 HotKeyReadCache（无锁子集 + 请求合并）；
- ConcurrencyMetrics 观测队列深度 / 分片利用率 / 等待 / 延迟。

## IO 架构（Phase 8）

```text
GET → ColdStorageEngine → BlockCache（LRU，off-heap 池化）
  hit  → 解码
  miss → MmapSSTableReader（MappedByteBuffer 零拷贝 + CRC）
FileChannelSSTableReader 保留为 baseline（benchmark 对比/降级）
```

- mmap 冷读零拷贝；MemoryPool（DirectByteBuffer 大小类池）管理缓存缓冲；
- IOStatistics 观测 readCount / cacheHit / cacheMiss / mappedBytes / 延迟。

## 生产基准（Phase 9）

- 三级基准：A 内存引擎（GET 4.7M / SET 4.4M ops/s）、B 服务端（pipeline64
  峰值 218–231K，目标 500K 未达——瓶颈在协议/调度层）、C 生产全链路
  （115–178K ops/s，P99 <5ms）；
- 容量模型与部署画像：docs/benchmark/capacity-model.md、
  deployment-profile.md；详见 docs/benchmark/phase9-* 报告。

## 生产化与优化（Phase 10）

- 响应批处理（自适应 batch=64 + 排空 flush）与回调式执行（对象削减）：
  Level B pipeline64×500 218–231K → 465K ops/s，pipeline128 → 1.14M；
- YAML 配置（config/application.yaml）、`INFO` 指标命令、优雅停机
  （drain + WAL force + checkpoint）。

## 分布式集群（Phase 11）

```text
Client → ClusterClient（slot 路由）→ MetadataServer（拓扑）
    → Shard Leader（ClusterNode）
        → Raft Group（Follower / Candidate / Leader）
            → ReplicatedStorageEngine
                → TieringStorageEngine（MemTable / WAL / SSTable）
```

- 哈希槽：CRC16/CCITT + 16384 slot（ADR-0035），与 Redis Cluster 语义一致，
  100K 键三 shard 分布 33.2% / 33.2% / 33.3%，路由开销仅 ~23ns/op；
- 元数据服务：JOIN / 拓扑查询 / leader 变更（ADR-0036）；
- 最小真实 Raft：随机化选举超时 + 心跳 + 日志复制（prevLog 校验 +
  nextIndex 回退）+ commit/apply（ADR-0037/0038），非简化假共识；
- 复制适配器：写经 Raft 日志复制后 apply 本地引擎，不改 MemTable/WAL/
  SSTable；读取走 leader 本地引擎；
- 基准（进程内原型，见
  [cluster-report.md](docs/benchmark/cluster-report.md)）：复制写 154K
  ops/s（P99=0.027ms）、读 750K ops/s（P99=4μs）、复制滞后 ≤35ms
  （心跳周期约束）、选举 124–310ms（目标 <5s ✅）、51 项新测试；
- 限制（如实声明）：Raft 消息进程内直调（无 TCP）、日志内存态（无磁盘
  持久化）、静态分片（无在线 slot 迁移），见 ROADMAP TD-022~025。

## 分布式生产化（Phase 12）

```text
RaftNode
  ├── RaftLog（分段文件 + CRC32C + SYNC/ASYNC/NONE + 尾部截断恢复）
  ├── RaftPersistentState（term / votedFor / commitIndex 落盘）
  ├── SnapshotManager（快照压缩 + InstallSnapshot 追赶）
  └── RaftTransport
        ├── LocalRaftTransport（测试/回退）
        └── NettyRaftTransport（TCP：连接复用 + RequestId + 超时重试）
```

- 持久化：重启后 term / 日志 / commitIndex 完整恢复（ADR-0039/0040）；
- 快照：日志超阈值自动压缩，重启 = 快照恢复 + 剩余日志重放；
- TCP RPC：AppendEntries / RequestVote / InstallSnapshot 二进制协议，
  连接复用、超时（3s）、幂等重试（ADR-0041）；
- 复制优化：CommitNotifier 提交后立即补发，滞后 13–35ms → **<1ms**
  （目标 <5ms ✅，ADR-0042）；
- 在线迁移：INIT→COPYING→VERIFYING→SWITCHING→DONE，checkpoint 续传 +
  CRC 校验 + 原子切换（ADR-0043）；
- 基准（[distributed-production-report.md](docs/benchmark/distributed-production-report.md)）：
  TCP 提交 P50=0.65ms / P99=2.16ms，RPC P50=100μs（单连接），
  迁移 16.1MB/s + 恢复 549ms/90K。

## 分布式优化（Phase 13）

- **批量/流水线复制**（ADR-0044）：batch AppendEntries（maxBatchEntries/
  maxBatchBytes/flushInterval）+ 多 in-flight + group commit，
  TCP 吞吐 700–1,359 → **9,220 ops/s**（64 并发写者）；
- **游标迁移**（ADR-0045）：单次扫描 + `slot-{start}.cursor`（CRC 保护）
  + PAUSED/恢复/崩溃续传，1KB 负载 **244.8MB/s**；
- **安全 RPC**（ADR-0046）：TLS（PEM 证书）+ Token 认证（含过期）+ 
  TokenBucket 限流（`ERR RATE_LIMIT`）；
- **元数据 Raft 化**（ADR-0047）：MetadataRaftGroup + MetadataClient，
  JOIN/拓扑/slot 归属/迁移状态走 Raft 日志，leader 故障转移 115ms；
- 基准：[phase13-report.md](docs/benchmark/phase13-report.md)；
  部署：[distributed-deployment.md](docs/deployment/distributed-deployment.md)。

## 生产加固（Phase 14）

- **批量写**（ADR-0048）：`MemTable.applyBatch`（单段单锁 + 版本预分配）
  + WAL 批量追加；
- **自适应 Flush/复制**（ADR-0049/0050）：AdaptiveFlushController +
  ReplicationController + `putAsync`（超时/取消/重试）；
- **安全升级**（ADR-0051）：HMAC-SHA256 签名 + nonce 防重放 + 双密钥
  轮换 + mTLS（ONE_WAY/MUTUAL）；
- **元数据持久化**（ADR-0052）：FileRaftLog + MetadataSnapshot，重启
  拓扑保留（194ms）；
- **故障注入**（5/5 通过）与跨机指南：
  [failure-injection.md](docs/testing/failure-injection.md) /
  [cross-machine-guide.md](docs/deployment/cross-machine-guide.md)；
- 基准：[phase14-production-report.md](docs/benchmark/phase14-production-report.md)
  （100B 迁移 18.3MB/s、Raft 37.3K ops/s，两个目标未达已如实记录）。

## 生产验证（Phase 15）

- **流式迁移**（ADR-0053）：单次快照扫描 + `MigrationStreamCursor` 游标
  （CRC + pause/resume/recover）+ 版本屏障 + 动态 batch；修复每批重建
  O(N) 快照的隐藏 O(N²) 行为，100B 迁移 2.9 → 59.8 MB/s；
- **全异步提案**（ADR-0054）：`RaftNode.proposeBatch`（N 请求 → 单次
  AppendEntries）+ `AsyncReplicationClient`（有界队列背压 + 内联批量
  drain + leader 变更重试）；1/64/256 写者 129/259/331K ops/s，
  P99 = 0.009/3.071/9.824ms；
- **证书生命周期**（ADR-0055）：CertificateManager（加载/校验/过期/
  原子轮换）+ CertificateWatcher（文件监听），轮换 p50=13.5ms，
  已有连接不中断；
- **混沌验证**（ADR-0053~0056 支撑）：16 项混沌测试（延迟/丢包/分区/
  磁盘慢/leader 击杀/混合故障/法定人数丢失），三轮稳定；发现并修复
  Raft 缺陷——冲突截断的未提交提案被新条目虚假完成；
- **可观测性**（ADR-0056）：ClusterMetricsRegistry（raft_proposal_qps /
  raft_commit_latency / raft_replication_lag / migration_speed /
  migration_cursor / migration_remaining / certificate_expire_time）+
  `INFO CLUSTER`（node/role/term/leader/slot）；
- 文档：[混沌报告](docs/testing/phase15-chaos-report.md)、
  [基准报告](docs/benchmark/phase15-production-validation-report.md)、
  [评审报告](docs/review/phase15-production-validation-review.md)。

## Multi-Raft 架构演进（Phase 16）

- **Region 抽象**（ADR-0057）：键范围 [startKey, endKey) + confVer/version
  纪元 + NORMAL/SPLITTING/MERGING/TOMBSTONE；RegionManager 路由/
  分裂/合并，旧纪元请求显式拒绝；
- **Multi-Raft**（ADR-0058）：MultiRaftNode + RaftGroupManager（每 Region
  独立 Raft 组）+ MultiRaftEndpoint（单端口组前缀路由，RaftNode API
  零改动）；吞吐随组数近似线性扩展（2 组 2.02×、4 组 3.68×）；
- **零拷贝批量写**（ADR-0059）：RawMutation 所有权转移 +
  MemTable.applyRawBatch（平面桶分组 + 单段单锁）+ SkipList 单次查找；
  100B 迁移 59.8 → 82.7 MB/s；
- **放置控制**（ADR-0060）：PlacementManager 分布/均衡检查/leader
  转移（epoch confVer 推进），自动 rebalance 暂缓；
- **混沌验证**：ChaosClusterTest 20 项（Region 级故障隔离），发现并
  修复 Raft 缺陷——新 leader 不回填滞后副本（心跳不匹配回退 nextIndex）；
- **可观测性**：RegionMetricsRegistry + `INFO REGIONS`
  （region/leader/epoch/size/state）；
- **跨机部署**：[Docker Compose + netem 混沌](docs/deployment/phase16-cross-machine.md)
  （ClusterMain 三节点入口）；
- 基准：[phase16-multiraft-report.md](docs/benchmark/phase16-multiraft-report.md)；
  评审：[phase16-multiraft-review.md](docs/review/phase16-multiraft-review.md)。

## Region 生命周期（Phase 17）

- **Region Split**（ADR-0061）：NORMAL→SPLITTING→SPLIT_READY→NORMAL +
  PREPARE/SNAPSHOT/INSTALL/COMMIT/CLEANUP 五阶段；分裂窗口写缓冲，
  10000 并发写无丢失；1M 键（外推）<1s；
- **Region Merge**（ADR-0062）：PREPARE→LOCK→TRANSFER→UPDATE_META→
  TOMBSTONE；右→左零拷贝搬迁，故障后状态重置可重试；1M 键（外推）
  <1s；
- **并行迁移**（ADR-0063）：按段分片 + chunk 检查点 + 8 worker，
  100B 209.1 MB/s（>150 ✅）、1KB 986、10KB 1952 MB/s；
- **真实 Leader Transfer**（ADR-0064）：TimeoutNow 立即选举 + 日志追平
  校验，24ms（<500ms ✅）；200ms 延迟 + 10% 丢包下仍成功；
- **Redis Cluster Gateway**：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS，
  非本地键返回 `MOVED slot host:port`；GET 3.68M / SET 1.67M ops/s；
- **自动均衡**（ADR-0065）：BalanceScheduler 检测 region/leader/disk/cpu
  压力并生成 BalancePlan（epoch 保护，不自动执行危险迁移）；
- **可观测性**：INFO RAFT / INFO MIGRATION（leader_transfer_total /
  election_total / proposal_latency / migration_bytes / migration_speed /
  region_merge_count）；
- 文档：[基准报告](docs/benchmark/phase17-region-report.md)、
  [评审报告](docs/review/phase17-region-lifecycle-review.md)。

## 分布式生产集成（Phase 18）

- **统一路由**（ADR-0066）：RoutingTable（键范围 + slot 区间 + epoch +
  leader + raftGroup）+ RoutingCache（陈旧自刷新）+ RouteEpochGuard；
  MOVED/ASK/TRYAGAIN 语义统一；
- **真实 TCP 网关**（ADR-0068）：NettyClusterGateway（EventLoop →
  RESP → CommandDispatcher → UnifiedRouter），pipeline 批量 flush；
  GET 719K / SET 590K ops/s（>500K/200K ✅）；
- **Split/Merge 与 Raft 联动**（ADR-0067）：RegionRaftMigrationManager
  （子/合并组创建 + 路由原子切换 + 失败回滚 + 恢复幂等）；
- **生产化迁移**：ByteRateLimiter（限速）+ MigrationScheduler（IO 压力/
  backlog 自适应）+ migration_remaining/error 指标；100B 209MB/s；
- **跨节点部署**（ADR-0069）：[docker-compose.cluster.yml](deploy/docker-compose.cluster.yml)
  + CrossMachineChaosTest（20 项：击杀/分区/恢复/快照追赶/迁移中断）；
- **可观测性**（ADR-0070）：MetricsExporter（Prometheus 格式）+
  ProductionInfo（INFO CLUSTER 聚合 Region/Raft/Migration/Gateway）；
- 文档：[基准报告](docs/benchmark/phase18-production-report.md)、
  [评审报告](docs/review/phase18-production-integration-review.md)。

## MVCC 与事务引擎（Phase 19）

- **MVCC**（ADR-0071）：底层键 `[userKey][type][startTS][commitTS]` +
  MvccStorageEngine adapter + 内存版本索引（启动重建）；
- **时间戳**（ADR-0072）：TimestampOracle（原子单调/批量/恢复不回退）+
  HybridLogicalClock（回拨安全）；
- **事务**（ADR-0073）：Percolator 2PC（BEGIN→Prewrite→Commit/Rollback）
  + TransactionCoordinator 跨 Region 2PC（参与者键归属，无部分提交）；
- **锁与冲突**（ADR-0074）：LockTable（TTL 防永久锁）+ 写写/读写/锁冲突；
- **恢复**（ADR-0076）：超时回滚 / primary 补完 / 无永久锁；
- **GC**（ADR-0075）：SafePoint + 保留最新版本（19–29MB/s，未达 100，
  如实登记 TD-041）；
- **基准**：MVCC GET 3.1–4.7M ops/s、单区事务 70.8–204.6K txn/s、
  冲突检测 2.1–7.6M ops/s；
- 文档：[MVCC](docs/architecture/mvcc.md) / [事务](docs/architecture/transaction.md) /
  [一致性](docs/architecture/consistency.md) /
  [基准](docs/benchmark/phase19-mvcc-report.md) /
  [评审](docs/review/phase19-mvcc-transaction-review.md)。

## 事务生产化与存储优化（Phase 20）

- **批量 GC**（ADR-0078）：`mvcc/gc` BatchGcExecutor（索引规划 + 分段
  批量物理删除 + 并行 worker），107–285MB/s（>100 ✅，TD-041 关闭）；
- **网关自动事务**（ADR-0079）：GET=快照读、SET/DEL=单键事务、
  MGET=一致快照、MSET=跨 shard 2PC；RESP 不变（TD-042 关闭）；
- **持久化 MVCC 索引**（ADR-0080）：Writer/Reader/Snapshot + 增量重建；
- **事务日志 Raft 持久化**（ADR-0081）：COMMIT 决策先落盘 + 恢复重放
  （无幻影提交 / 无丢失提交）；
- **可观测性**：INFO TRANSACTION / INFO MVCC、Prometheus
  （txn_abort/recovery、mvcc_versions/gc_deleted、redis_txn_latency）；
- **基准**：网关 GET 2.0–6.9M、SET 141–389K ops/s、单区事务
  324–651K txn/s、跨区 62–158K txn/s、恢复 1–4ms，全部达标；
- 文档：[基准](docs/benchmark/phase20-report.md) /
  [评审](docs/review/phase20-transaction-production-review.md) /
  [混沌](docs/testing/phase20-chaos-report.md)。

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml） |
| 测试 | JUnit 5（单元） + 集成测试（tests/） + JMH 压力测试（benchmarks/） |
| 网络 | Netty 4.1 事件循环模型（已引入，ADR-0003 / ADR-0006） |

## 目录结构

```text
tiering-kv/
├── .codex/                              # AI Agent 工程控制中心
│   ├── MASTER_PROMPT.md                 # Agent 最高规则
│   ├── DEVELOPMENT_RULES.md             # 开发规范
│   ├── AGENT_CONTEXT.md                 # 当前项目状态
│   ├── CODE_REVIEW_RULES.md             # AI 代码审查规则
│   ├── RELEASE_RULES.md                 # 发布流程
│   └── tasks/                           # 阶段任务文件
│       ├── phase0-init.md
│       ├── phase1-protocol.md
│       ├── phase2-storage.md
│       ├── phase3-cache.md
│       └── phase4-benchmark.md
│
├── docs/
│   ├── requirements/                    # 需求（requirements + acceptance）
│   ├── architecture/                    # 架构设计（overview / storage / network / concurrency）
│   ├── adr/                             # 架构决策记录（ADR-0001 ~ 0038）
│   ├── design/                          # 详细设计（protocol / memory / lsm / bitcask / eviction）
│   ├── benchmark/                       # 性能报告（计划 + 报告占位）
│   ├── review/                          # 技术评审
│   └── operations/                      # 运维文档
│
├── src/
│   ├── main/                            # 模块骨架：network / protocol / command / storage / cache / scheduler / memorypool / metrics / config
│   └── test/
│
├── tests/                               # 自动化测试（unit / integration / stress / chaos）
│
├── benchmarks/                          # 性能测试（throughput / latency / memory / migration）
│
├── scripts/                             # 工程脚本（build / benchmark / stress-test / release）
│
├── config/                              # 配置（tiering-kv.yaml / benchmark.yaml）
│
├── examples/                            # 使用示例
│
├── tools/                               # 开发工具（profiler / analyzer）
│
├── .github/workflows/                   # CI/CD（build / test / benchmark）
│
├── README.md
├── ROADMAP.md
├── CHANGELOG.md
├── CONTRIBUTING.md
├── LICENSE
└── .gitignore
```

> `pom.xml`（Maven 构建）按最初目录规范保留；`src/main/<module>` 为框架骨架目录，
> Java 代码落地时映射到 `src/main/java/io/tieringkv/<module>/`（TD-004）。

## Codex 工程控制文件

- [MASTER_PROMPT.md](.codex/MASTER_PROMPT.md)：主控提示词，定义角色、目标与流程。
- [DEVELOPMENT_RULES.md](.codex/DEVELOPMENT_RULES.md)：开发规范（ADR / Git / TDD / 安全机制）。
- [AGENT_CONTEXT.md](.codex/AGENT_CONTEXT.md)：项目长期上下文，每次会话先读取。
- [CODE_REVIEW_RULES.md](.codex/CODE_REVIEW_RULES.md)：代码审查规则与门禁。
- [RELEASE_RULES.md](.codex/RELEASE_RULES.md)：发布流程（SemVer + tag + 回归门禁）。
- [tasks/](.codex/tasks/)：阶段任务文件（phase0–phase4）。

## 开发流程

每个阶段严格遵循：

```text
需求 → 设计 → ADR → 实现（TDD） → 测试 → 性能验证 → Git Commit
```

Git 分支策略：

```text
main（稳定）
 └── develop（集成）
      ├── feature/protocol
      ├── feature/storage-engine
      ├── feature/cache-policy
      ├── feature/io-optimization
      └── feature/benchmark
```

Commit 采用 Conventional Commit（feat / fix / refactor / test / perf / docs /
build / chore），每个阶段至少一次语义化提交。

## 文档

- 需求：[requirements.md](docs/requirements/requirements.md) /
  [acceptance.md](docs/requirements/acceptance.md)
- 架构：[overview.md](docs/architecture/overview.md) 与
  [storage](docs/architecture/storage-architecture.md) /
  [network](docs/architecture/network-architecture.md) /
  [concurrency](docs/architecture/concurrency-model.md)
- 设计：[docs/design/](docs/design/)（protocol / memory / lsm / bitcask / eviction）
- Benchmark：[benchmark-plan.md](docs/benchmark/benchmark-plan.md)，报告 Phase 9 填充
- 评审：[docs/review/](docs/review/)；运维：[docs/operations/](docs/operations/)
- 路线图：[ROADMAP.md](ROADMAP.md)
- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- ADR 索引：[docs/adr/](docs/adr/)（0001–0005）
- 贡献：[CONTRIBUTING.md](CONTRIBUTING.md)；License：[LICENSE](LICENSE)

## 性能目标

| 指标 | 目标 |
| --- | --- |
| 热点 GET P50 / P95 / P99 | < 0.5ms |
| 并发连接 | 1k / 10k / 100k |
| 内存占用（对比纯内存 Redis） | 降低 60%–80% |

## 快速开始

```bash
mvn test                  # 单元 + 集成 + 基准 + 混沌（Phase 1–15，650 个用例）
mvn -q exec:java          # 启动服务，默认 0.0.0.0:6379
redis-cli -p 6379         # PING / ECHO / SET / GET / DEL / EXISTS
```

当前支持命令：PING / ECHO / SET / GET / DEL / EXISTS（Phase 1，RESP2）。
