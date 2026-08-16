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

## P2 — 功能深度（主体完成，2026-08-15 归档）

- 归档评审：docs/review/phase70-p2-functional-depth-review.md；
  BIT/GEO、JSON 路径、时序、向量多集合、跨集群 2PC 已交付；
  OBJECT/ACL/SCRIPT ✅（ADR-0340，2026-08-15：OBJECT 编码/计数、
  ACL 只读子集、SCRIPT SHA1 注册表；EVAL 显式不可用，无 Lua）；
  RESP3 完整类型 ✅（ADR-0341，2026-08-15：null `_`、HELLO/CONFIG
  map、集合族 set、HGETALL map，字节级 wire 测试双口径）——P2
  全部完成；评审：docs/review/phase71-object-acl-script-review.md、
  docs/review/phase72-resp3-full-types-review.md；技术债审计关闭
  12 项（TD-008/009/010/011/016/017/019/032/033/035/037/048），
  剩余开放 6 项（TD-001/015/038 部分/044/046/049）。

- Redis 兼容面：BIT/GEO ✅（ADR-0334/0335，2026-08-15：SETBIT/
  GETBIT/BITCOUNT/BITPOS/BITOP + GEOADD/GEOPOS/GEODIST/GEOHASH/
  GEOSEARCH/GEORADIUS(BYMEMBER)，ZSET+52 位 geohash 存储，
  Redis 官方文档基准通过）；JSON 路径 ✅（ADR-0336，2026-08-15：
  JSON.SET/GET/DEL/TYPE/ARRAPPEND/ARRLEN/OBJKEYS/OBJLEN/STRLEN/
  NUMINCRBY，jackson-databind + 自研路径子集，RedisJSON 文档示例
  路径语义通过）；时序 ✅（ADR-0337，2026-08-15：TS.RANGE 桶聚合
  AVG/SUM/MIN/MAX/COUNT/FIRST/LAST + COUNT、TS.INCRBY 原子累加、
  TS.MRANGE 多键、TS.REDUCE 全序列聚合）；OBJECT/SCRIPT/ACL 命令族，
  RESP3 完整类型；向量多集合 ✅（ADR-0338，2026-08-15：
  VectorCollectionRegistry 集合隔离 + dirty 跟踪 + 自动 checkpoint +
  loadAll 恢复；VECTOR.* 支持 COLLECTION 前缀 + LIST/DROP/CHECKPOINT；
  SQL 混合检索集合接线）；跨集群 2PC ✅（ADR-0339，2026-08-15：
  CrossClusterTxnCoordinator/Participant + TXN_PREPARE/COMMIT/
  ROLLBACK 阶段事件 + 携带 mutations 的决策日志恢复 + LWW 冲突
  收敛，双 endpoint E2E 通过）；
- SQL 索引真正接线执行器（谓词/join）、EXPLAIN 落地；
- 向量多集合命名空间、自动 checkpoint、混合检索 SQL 化；
- 时序 TS.RANGE/聚合/下采样/压缩；
- JSON 路径操作命令族；
- 跨集群 2PC（最大项，复制 + 事务联动）。

## P3 — 可靠性 / 可观测性 / 运维

- 真实磁盘故障注入 ✅（ADR-0342，2026-08-15：block-device-chaos.sh
  修正（loop 自动分配/真实填满/cleanup 幂等）+ RealBlockDeviceExerciseTest
  baseline/disk-full/readonly 闭环 + CI block-device-chaos job 接线；
  真实 Runner 演练发现并修复 WAL 只读恢复缺陷；TD-044 关闭、
  TD-046/049 部分关闭（容器级注入持续跟踪））；真实网络 netem 混沌
  ✅（ADR-0343，2026-08-15：镜像安装 iproute2（修复静默 no-op）、
  network-chaos.sh delay/loss/partition/recover + 应用后校验、
   RealNetworkChaosTest 三阶段真实 RESP 演练、CI container-e2e
   全阶段接线；真实 Runner 门禁通过——6 轮修复：脚本可执行位 →
   NET_ADMIN 能力 → 网关 RESP2 合规 → 运行时 RPC 地址表 →
   CR 剥离读取 → 冒烟独立脚本（嵌套引号）；container-e2e
   delay/loss/partition/recover 全阶段真实执行）；
- 向量/复制/多模型/备份 Metrics 收口（ADR-0344，2026-08-15：
  observability 注册表 + INFO sections + `/metrics/prometheus`
  端点 + 向量/备份喂数；真实 Runner 门禁 3/3 全绿；复制/多模型
  喂数（ADR-0345：pipeline/命令注入 + W3C traceparent + INFO tracing
  section + GatewayRuntime 命令 span）完成，全量回归
  14907 次测试执行（Surefire 口径）
  0 failures）；
- OpenTelemetry span 透传；
- 备份恢复纳入向量索引与复制水位；
- CI 卫生：JFR/日志 artifact、测试分片、缓存优化。

P3 收口完成（2026-08-16）：向量 checkpoint 水位（VECTOR.CHECKPOINT →
VectorMetricsRegistry 计数/watermark + Prometheus）、备份纳入向量索引
（BackupManager 可选 VectorIndexStore + RestoreManager.restoreVectorIndex）
与复制水位（BackupMetricsRegistry attach LagTracker）、CI 卫生
（concurrency 取消重复 + Maven cache + 超时 + surefire/容器日志
artifact 上传）；测试分片/JFR 登记 TD-051 缓行（全量单 job ~6 分钟、
并行分片与 Raft/端口时序风险不成比例）。

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
| P2 | 功能深度 | 2–4 周 | ~600 |（BIT/GEO/JSON/TS/向量集合/跨集群 2PC 已完成，余 OBJECT/SCRIPT/ACL 与 RESP3 完整类型）
| P3 | 混沌/可观测性 | 1–2 周 | ~200 |

每个阶段走"ADR → TDD → 全量回归 → 真实 Runner 门禁"。
