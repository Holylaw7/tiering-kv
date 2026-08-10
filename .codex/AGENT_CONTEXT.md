# AGENT_CONTEXT — 项目长期上下文

> 每次会话开始时阅读本文档与仓库状态，快速恢复上下文。

## 1. 项目概况

Tiering-KV：Mini Redis 冷热分层存储引擎。核心能力：
RESP 协议兼容、内存 + 磁盘冷热分层、LFU/ARC 热度管理、异步冷热迁移、
Bitcask/LSM 持久化、高并发网络、mmap 零拷贝、分段锁/无锁、
Bloom Filter、自研 Memory Pool。

当前定位：高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎（RESP + WAL +
MemTable + SSTable + LFU/ARC + 自动调度 + Key Sharding，Phase 1–7 完成）；
集群/pub/sub/正式性能基线为演进目标（Phase 8–10）。

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

## 2. 当前状态

- 阶段：**Phase 10（Advanced Optimization & Productionization）✅ 已完成**
  （Phase 0–9 全部完成）；
- 最近提交：`feat: add graceful shutdown`（详见 git log）；
- 基线：tag `phase-0`；分支策略：feature/* 合并入 develop，main 保持稳定；
- 下一步：项目按 10 阶段路线图全部完成；可进入独立进程复测、集群扩展或
  对外发布准备（等待用户指令）。

项目里程碑：**10 阶段路线图全部完成（2026-08-10）**；最终定位 =
完整冷热分层存储系统（RESP + Async Server + Shard + Memory + LFU + WAL +
LSM/SSTable + Bloom + Compaction + Migration + mmap + BlockCache +
Production Runtime），14 模块能力矩阵全 ✅。

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

## 5. 仓库布局

```text
tiering-kv/
├── .codex/          # 工程控制中心（规则 + tasks/）
├── docs/
│   ├── requirements/  # requirements.md + acceptance.md
│   ├── architecture/  # overview + storage/network/concurrency
│   ├── adr/           # ADR-0001 ~ 0005
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
| 10 | 生产化 | 未开始 |

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

## 8. 会话启动清单

1. `git status` + `git log --oneline -10`；
2. 阅读 README.md、ROADMAP.md、CHANGELOG.md；
3. 阅读 .codex/DEVELOPMENT_RULES.md、.codex/CODE_REVIEW_RULES.md、
   .codex/RELEASE_RULES.md；
4. 阅读 docs/adr/ 目录与 .codex/tasks/ 对应任务文件；
5. 对照 ROADMAP 与本文档确认当前阶段、未完成任务与技术债。
