# ROADMAP

> 过程门：每次迭代（Phase）都执行「需求 → 设计 → ADR → 实现（TDD） → 测试 →
> 性能验证 → Git Commit」七个环节；下述 0–10 为交付路线图。

## 阶段总览

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ 完成（2026-08-09） |
| 1 | RESP 协议 | ⏳ 未开始 |
| 2 | 内存 KV 核心 | ⏳ 未开始 |
| 3 | LFU / ARC 热度管理 | ⏳ 未开始 |
| 4 | Bitcask 持久化 | ⏳ 未开始 |
| 5 | LSM Tree | ⏳ 未开始 |
| 6 | 冷热迁移 | ⏳ 未开始 |
| 7 | 并发优化 | ⏳ 未开始 |
| 8 | mmap / Memory Pool | ⏳ 未开始 |
| 9 | Benchmark 压力测试 | ⏳ 未开始 |
| 10 | 生产化完善 | ⏳ 未开始 |

## Phase 0 — 工程初始化 ✅

- 交付：Git 仓库（main/develop）、目录骨架、README、ROADMAP、CHANGELOG、
  Maven 骨架、ADR-0001~0003。
- 验收：`mvn test` 通过；git 历史含本次语义化提交；ADR 覆盖架构 / 存储 / 并发。
- ADR：[0001](docs/adr/ADR-0001-project-architecture.md)、
  [0002](docs/adr/ADR-0002-storage-strategy.md)、
  [0003](docs/adr/ADR-0003-concurrency-model.md)

## Phase 1 — RESP 协议

- 目标：RESP2 编解码（SET / GET / DEL / PING / ECHO / EXISTS 等）、协议错误处理。
- 交付：protocol 模块接口与实现、单元 + 集成测试。
- 预计 ADR：序列化协议选择（RESP2 vs RESP3）、网络模型细化。

## Phase 2 — 内存 KV 核心

- 目标：MemTable（分段哈希表）、TTL 支持、内存配额与淘汰回调。
- 交付：memory 模块、并发单元测试。
- 预计 ADR：内存数据结构选择。

## Phase 3 — LFU / ARC 热度管理

- 目标：访问采样、LFU 衰减、ARC 自适应列表、冷热判定阈值。
- 交付：eviction / cache 模块、热度模拟测试。
- 预计 ADR：淘汰算法选择。

## Phase 4 — Bitcask 持久化

- 目标：追加写日志、全量内存索引、崩溃恢复、后台 merge。
- 交付：wal / storage（BitcaskEngine）、恢复与合并测试。
- 预计 ADR：WAL 一致性策略。

## Phase 5 — LSM Tree

- 目标：MemTable → SSTable、层级合并、Bloom Filter。
- 交付：sstable / compaction 模块、读写放大观测。
- 预计 ADR：LSM 层级策略与压缩策略。

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
- 交付：benchmarks/ 压测套件与 docs/benchmark/ 报告。

## Phase 10 — 生产化完善

- 目标：配置化、优雅停机、监控指标、故障演练、部署文档。
- 验收：达到工程完整性的 Mini Redis。

## 技术债登记

| 编号 | 描述 | 来源 | 计划消除 |
| --- | --- | --- | --- |
| TD-001 | 单 Maven 模块；若模块耦合升高需评估拆分多模块 | ADR-0001 | Phase 7 前评估 |
| TD-002 | JDK 17 目标下暂不采用虚拟线程 | ADR-0003 | Phase 7 评估升级 JDK 21 |
