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
| 10 | 生产化完善 | ⏳ 未开始 |

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

## Phase 10 — 生产化完善

- 目标：配置化、优雅停机、监控指标、故障演练、部署文档。
- 验收：达到工程完整性的 Mini Redis。
- Phase 9 评审补充：协议/调度层优化——批量响应写、每请求对象数削减
  （TD-020）、ResponseSequencer 并发化、独立进程复测（预期 +20–40%）；
  以 JFR allocation/GC 为验收（TD-021）。

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
