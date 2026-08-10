# Tiering-KV

> 高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎
> （RESP + WAL + MemTable + SSTable + 自动调度 + Key Sharding +
> Raft 持久化集群 + Netty RPC + 在线迁移）。

**阶段状态：Phase 12（分布式生产化）✅（Phase 0–11 全部完成 ✅）**

## 项目定位

**当前定位**：高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎——已完成 RESP
协议、内存引擎、LFU/ARC 淘汰、WAL 持久化、SSTable 冷层、自动 Flush /
异步迁移 / 背压、Key Sharding 异步执行与热点治理（Phase 1–10），并完成
分布式集群基础：16384 hash slot 路由、元数据服务、最小真实 Raft（选举 /
心跳 / 日志复制 / 提交）与故障转移（Phase 11），以及分布式生产化：
Raft 日志持久化 + 快照、Netty TCP RPC、复制滞后优化（<1ms）、在线
Slot 迁移（Phase 12）；面向 redis-cli 与主流客户端提供 PING / ECHO /
SET / GET / DEL / EXISTS 能力。

**边界（如实声明）**：仍为教学/工程级实现，暂不宣称"高性能 Redis 替代品"；
分布式为真实 TCP + 持久化原型（RPC 串行、无 TLS、元数据单机），
pub/sub、Lua、RESP3 与正式性能基线（内存降低 60%–80%）为后续演进方向。

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
mvn test                  # 单元 + 集成 + 延迟冒烟测试（47 个用例）
mvn -q exec:java          # 启动服务，默认 0.0.0.0:6379
redis-cli -p 6379         # PING / ECHO / SET / GET / DEL / EXISTS
```

当前支持命令：PING / ECHO / SET / GET / DEL / EXISTS（Phase 1，RESP2）。
