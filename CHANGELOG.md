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
