# Optimization Roadmap（v4.0 收口后）

基线：v3.7.1 GA + v4.0 M1/M2/M3 完成；14640 tests / 40 主模块 /
21 项开放技术债。本文档为后续优化/添加的完整方案与执行顺序。

## P0 — v4.0 M4 生产收口（路线图既定，ADR-0322）

| 项 | 内容 | 验收 |
| --- | --- | --- |
| Operator 完整化 | CRD 备份/恢复、滚动升级、多集群编排；Controller reconcile + 状态上报 | Operator E2E |
| Jepsen 外部化 | harness 接入真实 Runner：分区/网络注入脚本化 + 报告 | 真实 Runner 混沌报告 |
| 冷/热性能基线 | cold-cache 口径（TD-009）+ 三级基准（内存/服务端/生产全链路） | phase61 报告 |
| 容量模型 | TD-019：吞吐/延迟/内存/磁盘四维模型 | capacity-model 更新 |
| GA 门禁 | 7/7 ×2 + Jepsen 报告 + 容量模型 | 门禁全绿 |

## P1 — 技术债清偿（按价值排序）

### P1a / P1b 状态（2026-08-15）

- P1a 存储引擎三件套 ✅：迁移队列批量/准入/动态 worker（ADR-0325）、
  Leveled compaction（ADR-0323）、MemTable 轮转 + 生产接入（ADR-0324）；
- P1b 缓存/淘汰 ✅：ARC byte 容量（ADR-0326）、Segment LFU + Async
  Buffer（ADR-0327）、HotCache version check（ADR-0328）；
- 评审：docs/review/phase62-p1a-storage-engine-review.md、
  docs/review/phase63-p1b-cache-eviction-review.md；TD-005/006/012/
  013/014/018 已关闭。
- P1c 并发/性能 ✅（2026-08-15）：WAL 并行恢复（ADR-0329）、命令
  路径 allocation 基线（ADR-0330，64B/请求）、JDK 21 虚拟线程 POC
  （ADR-0331，941K ops/s）；报告 docs/benchmark/phase64-*。

### P1a 存储引擎

- leveled compaction（TD-012）：分层合并，降低读放大；
- Active/Immutable MemTable 轮转（TD-013）：写入不停顿；
- 迁移队列准入/批量/worker 扩缩容（TD-014）。

### P1b 缓存/淘汰

- ARC byte 口径（TD-005）；
- Segment LFU + Async Buffer（TD-006）；
- Hot Cache version check（TD-018）。

### P1c 并发/性能

- WAL 并行恢复（TD-007）；
- request→response 对象数优化（TD-020/021，JFR 验收）；
- 动态重分片（TD-017）；
- JDK 21 虚拟线程 POC（TD-002）。

### P1d v4 模块增强

- HNSW 图检索（M1 限制）✅（ADR-0332，2026-08-15）：多层图 +
  贪心下降 + efSearch，20K×64 检索 P50 0.473ms / P99 0.847ms
  （旧暴力 9.9ms，下降约 11.7×），召回率 ≥0.9；
- 复制流水线（M3 限制）✅（ADR-0333，2026-08-15）：批量帧编码
  （标记 + 长度前缀 + CRC）、sendBatch/sendAsync + 成功/失败计数、
  水位周期 checkpoint、ConflictResolver 接口 + LWW 实现。
- 报告：docs/benchmark/phase65-hnsw-search-report.md。
- 评审：docs/review/phase65-p1d-v4-module-enhancement-review.md；
  TD-002/007/020/021 随归档关闭。

## P2 — 功能深度

- Redis 兼容面：BIT/GEO ✅（ADR-0334/0335，2026-08-15：SETBIT/
  GETBIT/BITCOUNT/BITPOS/BITOP + GEOADD/GEOPOS/GEODIST/GEOHASH/
  GEOSEARCH/GEORADIUS(BYMEMBER)，ZSET+52 位 geohash 存储，
  Redis 官方文档基准通过）；OBJECT/SCRIPT/ACL 命令族，RESP3 完整类型；
- SQL 索引真正接线执行器（谓词/join）、EXPLAIN 落地；
- 向量多集合命名空间、自动 checkpoint、混合检索 SQL 化；
- 时序 TS.RANGE/聚合/下采样/压缩；
- JSON 路径操作命令族；
- 跨集群 2PC（最大项，复制 + 事务联动）。

## P3 — 可靠性 / 可观测性 / 运维

- 真实磁盘故障注入（TD-049）、真实网络 netem 混沌；
- 向量/复制/多模型 Metrics 进 INFO + Prometheus 端点；
- OpenTelemetry span 透传；
- 备份恢复纳入向量索引与复制水位；
- CI 卫生：JFR/日志 artifact、测试分片、缓存优化。

## P4 — 工程现代化

- 多模块拆分评估（TD-001）；
- JDK 21 正式升级；
- 命令表驱动重构。

## 执行顺序与工作量

| 阶段 | 内容 | 预估 | 测试增量 |
| --- | --- | --- | --- |
| P0 | M4 收口 | 1–2 周 | ~300 |
| P1a | 存储引擎三件套 | 1–2 周 | ~250 |
| P1b | 缓存/淘汰 | 1 周 | ~150 |
| P1c | 并发/性能 | 1 周 | ~200 |
| P1d | v4 模块增强 | 1 周 | ~200 |
| P2 | 功能深度 | 2–4 周 | ~600 |
| P3 | 混沌/可观测性 | 1–2 周 | ~200 |

每个阶段走"ADR → TDD → 全量回归 → 真实 Runner 门禁"。
