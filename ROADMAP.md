# ROADMAP

> 过程门：每次迭代（Phase）都执行「需求 → 设计 → ADR → 实现（TDD） → 测试 →
> 性能验证 → Git Commit」七个环节；下述 0–10 为交付路线图。

## 阶段总览

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ 完成（2026-08-09） |
| 1 | RESP 协议 | ✅ 完成（2026-08-09） |
| 2 | 内存 KV 核心 | ✅ 完成（2026-08-10） |
| 3 | LFU / ARC 热度管理 | ⏳ 未开始 |
| 4 | Bitcask 持久化 | ⏳ 未开始 |
| 5 | LSM Tree | ⏳ 未开始 |
| 6 | 冷热迁移 | ⏳ 未开始 |
| 7 | 并发优化 | ⏳ 未开始 |
| 8 | mmap / Memory Pool | ⏳ 未开始 |
| 9 | Benchmark 压力测试 | ⏳ 未开始 |
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

## Phase 3 — LFU / ARC 热度管理

- 目标：访问采样、LFU 衰减、ARC 自适应列表、冷热判定阈值。
- 交付：eviction / cache 模块、热度模拟测试。
- ADR：[0004](docs/adr/ADR-0004-cache-policy.md)（已创建，实现阶段细化）。

## Phase 4 — Bitcask 持久化

- 目标：追加写日志、全量内存索引、崩溃恢复、后台 merge。
- 交付：wal / storage（BitcaskEngine）、恢复与合并测试。
- 预计 ADR：WAL 一致性策略。
- 格式基线：[ADR-0005](docs/adr/ADR-0005-persistence-format.md)。

## Phase 5 — LSM Tree

- 目标：MemTable → SSTable、层级合并、Bloom Filter。
- 交付：sstable / compaction 模块、读写放大观测。
- 预计 ADR：LSM 层级策略与压缩策略。
- 格式基线：[ADR-0005](docs/adr/ADR-0005-persistence-format.md)。

## Phase 6 — 冷热迁移

- 目标：异步升降级迁移、迁移一致性协议、背压与重试。
- 交付：scheduler 模块、故障注入测试。
- 预计 ADR：迁移一致性协议。

## Phase 7 — 并发优化

- 目标：分段锁细化、无锁读路径、热点 key 缓解。
- 交付：并发专项测试（JCStress 等）。
- 预计 ADR：锁机制选择。

## Phase 8 — mmap / Memory Pool

- 目标：mmap 零拷贝、自研 Memory Pool、off-heap 缓冲。
- 交付：io 优化模块、内存基准对比。
- 预计 ADR：IO 模型与内存池设计。

## Phase 9 — Benchmark

- 目标：1k / 10k / 100k 连接、P50/P95/P99、内存对比 Redis。
- 交付：benchmarks/ 压测套件与 docs/benchmark/ 报告（计划见
  [benchmark-plan.md](docs/benchmark/benchmark-plan.md)）。

## Phase 10 — 生产化完善

- 目标：配置化、优雅停机、监控指标、故障演练、部署文档。
- 验收：达到工程完整性的 Mini Redis。

## 技术债登记

| 编号 | 描述 | 来源 | 计划消除 |
| --- | --- | --- | --- |
| TD-001 | 单 Maven 模块；若模块耦合升高需评估拆分多模块 | ADR-0001 | Phase 7 前评估 |
| TD-002 | JDK 17 目标下暂不采用虚拟线程 | ADR-0003 | Phase 7 评估升级 JDK 21 |
