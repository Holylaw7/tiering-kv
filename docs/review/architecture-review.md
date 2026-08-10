# 架构评审（Architecture Review）

状态：已有 Phase 1 记录（2026-08-10）

模板：评审对象 / 结论 / 风险 / 行动项。
规则：.codex/CODE_REVIEW_RULES.md。

## Phase 1 评审意见（2026-08-10）

1. **协议层抽象正确**：`RespValue` sealed 类型体系避免将 RESP 当作简单字符串解析，
   利于后续 RESP3 / Pub/Sub / Transaction / Cluster 扩展。约束：保持 protocol 层
   独立，command 层不得直接操作 ByteBuf。
   → 结论：保持现状，纳入后续阶段约束。
2. **Netty pipeline 顺序修复是真实工程问题**（outbound 事件绕过 Encoder），已由
   集成测试捕获并修复。记录保留于 docs/review/code-review.md 与
   docs/architecture/network-architecture.md。
   → 结论：保留记录，作为后续阶段与面试素材。
3. **性能基线合理但定位需谨慎**：localhost / 单连接 / 无持久化 / InMemory 下
   P50=0.064ms 属正常基线，但不得宣称"高性能 Redis 替代品"。
   → 行动：README 定位措辞已修正为"RESP 兼容 KV Server 基础层"，性能与分层
     能力列为演进目标（ROADMAP Phase 2–10）。

## Phase 2 技术评审（2026-08-10）

1. **Storage SPI 抽象正确**：Command → StorageEngine → MemTable，未来可平滑
   演进 LSMStorage / BitcaskStorage / TieredStorage，避免 Phase 4/5 大规模重构。
   → 结论：保持，作为存储层唯一入口。
2. **64 段 SkipList + Striped Lock 合理**：相比 ConcurrentHashMap，换取有序遍历、
   范围扫描、SSTable Flush、LSM 兼容；MemTable → Immutable MemTable → SSTable
   → Compaction 方向确认。
3. **Tombstone 设计正确**：DEL 不物理删除，为 WAL replay / Snapshot /
   Compaction 保留删除历史（LevelDB / RocksDB 思路）。
4. **TTL 混合策略达标**：min-heap 主动清扫 + 惰性检查，避免全表周期扫描。
5. **全局 Iterator 为亮点**：64 段快照 + PriorityQueue k-way merge，后续 LSM
   Merge Iterator 可直接复用。
6. **⚠️ Benchmark 口径**：存储层 P99≈2.5μs 非端到端；已修正文档标注为
   Storage-layer baseline，网络端到端（回环）P99≈0.19ms。
7. **技术债确认**：迭代器弱一致（Phase 5 评估 MVCC iterator）；tombstone 未回收
   （Phase 5 compaction 移除）。

## Phase 3 架构评审（2026-08-10）

1. **装饰器接入正确**：TrackingStorageEngine 使存储与缓存逻辑解耦，符合
   Redis module / RocksDB wrapper / Spring AOP 思路。
2. **LFU 达到生产级基础**：TreeSet 快照索引 O(logN) 更新、O(1) 候选，
   优于 Map 全遍历 O(N)。
3. **衰减合理**：frequency × 0.5 周期衰减解决"老热点永久占用"。
4. **ARC 原型价值高**：T1/T2/B1/B2 解决 LFU 的热点突变适应问题。
5. **淘汰流程正确**：迁移先于删除，Phase 6 可直接接磁盘层。
6. **⚠️ 已修正**：`MigrationCallback(void)` → `TierMigration` 结果码
   （SUCCESS/FAILED/RETRY），删除仅在 SUCCESS 后执行；基准口径统一为
   "Eviction decision latency"（候选选择，不含迁移/IO）。
7. **技术债登记**：TD-005 ARC 容量单位改 byte（Phase 9 ADR）；TD-006 LFU
   全局同步段 → Segment LFU + Async Statistics Buffer + Periodic Merge
   （Phase 7，Caffeine 思路）。

## Phase 4 总体评价（2026-08-10）

1. **WAL 装饰器优秀**：StorageEngine → WALStorageEngine → MemTable，避免
   MemTable 感知持久化细节；后续可平滑扩展 LSMStorageEngine /
   TieredStorageEngine。
2. **WAL 格式达数据库级**：Magic+Version（TKV1 → 未来 TKV2 可判别）、
   CRC32C（防半写/断电尾部损坏）、TTL 相对时长 + timestamp（恢复重算绝对
   过期点，规避机器时间变化）。
3. **写策略合理**：ALWAYS / EVERY_SEC / NO 对齐 Redis AOF appendfsync；
   默认 EVERY_SEC 避免每 SET 一次 fsync 导致吞吐塌陷。
4. **恢复流程正确**：Segment Scan → CRC Verify → Replay → Truncate；
   中段损坏停止后续重放，避免状态不可预测。
5. **Checkpoint 方向正确**：offset → snapshot → restore → replay remaining；
   Phase 5 演进为 Checkpoint → SSTable → Manifest。
6. **⚠️ 基准口径**：当前 append 指标为 buffered mode（非逐条 fsync），已修正
   文档为 "WAL append throughput (buffered mode)"，并注明 ALWAYS 模式性能
   下降属正常，Phase 9 补测对比。
7. **迁移接口**：评审建议的 `TierMigration{SUCCESS, FAILED, RETRY}` 已在
   Phase 3 评审时提前落地（ADR-0013 + TierMigration/MigrationResult + 测试），
   Phase 5 迁移（文件写入/checksum/compaction）直接复用，无需再改。

### 技术债（新增）

- TD-007：WAL 恢复单线程（1M ≈ 1s，可接受；Phase 7 评估 parallel replay）；
- TD-008：Checkpoint 全量快照（Phase 5 演进为 Immutable MemTable →
  SSTable flush + Manifest，自然解决）。

## Phase 5 评审结论（2026-08-10）

1. **冷热链路闭环完成**：SET → WAL → MemTable → EvictionManager →
   ColdMigration → SSTable → Manifest；GET → pending → 新表 → 旧表 →
   Bloom → Index → Block → Value，接近 LevelDB/RocksDB/Cassandra 模型。
2. **LSM + WAL 选择正确**：WAL → MemTable → SSTable 即 LevelDB 基础模型，
   且更强调冷热分层（契合 Tiering-KV 定位）。
3. **SSTable 格式标准**：Data/Index/Bloom/Footer + CRC，元数据支撑后续
   Compaction / Recovery / TTL 清理。
4. **Bloom 达标**：bits-per-key=10，FPR 0.82%（<1% ✅）；xxHash/Murmur3
   留作后续可选优化。
5. **Compaction 关键语义完整**：latest wins / tombstone 删除 / TTL 清理，
   空间回收 73%。
6. **Migration 接口正确**：ColdMigration + MigrationResult（SUCCESS/FAILED/
   RETRY）已支持未来 disk full / IO error / checksum error。
7. **⚠️ 基准表述已修正**：SSTable 写改为 Peak 104MB/s / Average 85MB/s
   （冷启动 30MB/s 如实标注）。
8. **⚠️ page cache 影响已登记**：随机 GET P99 0.021–0.053ms 为热缓存口径；
   Phase 9 增加 cold-cache benchmark（TD-009）。
9. **技术债**：TD-010 pending 持久化（Migration WAL / Pending Manifest，
   Phase 6）；TD-011 自动 Flush（memory watermark + FlushScheduler，
   Phase 6）；TD-012 leveled compaction（Phase 7）。

## Phase 6 评审结论（2026-08-10）

1. **架构链路完整**：Client → RESP → TieringStorageEngine → MemTable +
   TieringController（Watermark → Flush/Migration Worker → SSTable/Cold）；
   Netty 事件循环与后台 worker 隔离，避免用户线程磁盘 IO 导致 RT 爆炸。
2. **ADR-0020 正确**：Async Worker Model（queue + worker pool + state
   machine），对齐 RocksDB background jobs / Kafka async flush / Cassandra
   compaction executor。
3. **Flush Scheduler 达标，含一项技术债**：水位（≥85%）+ entry 阈值触发；
   ⚠️ 快照式 Flush 非 Immutable MemTable → 已登记 TD-013（Phase 7 升级
   Active → Immutable 轮转）。
4. **Migration Scheduler 完成度高**：version guard 防止"迁移删除新数据"
   （T1 读旧值 → T2 迁移 → T3 更新 → T4 误删）竞态。
5. **MigrationLog 为亮点**：CRC32C + 状态机（PENDING/RUNNING/RETRY/
   SUCCESS/FAILED），对齐 Kafka offset log / RocksDB MANIFEST。
6. **Backpressure 合理**：CRITICAL awaitWritable → timeout → -ERR，
   比直接丢数据更安全。
7. **基准达标**：迁移 283–308K ops/s（目标 6×）、Flush 798–857K entries/s、
   2MB 配额下峰值 350KB。
8. **迁移 P99 如实报告**（1M ≈ 1.2–1.4s）：根因是 producer > consumer 的
   队列堆积，非单任务慢；生产由背压约束，Phase 7/9 候选：队列准入控制、
   迁移批量、worker 动态扩缩容（TD-014）。
9. **能力矩阵确认**：RESP/命令/内存/TTL/Tombstone/LFU/ARC/WAL/恢复/SSTable/
   Bloom/Compaction/迁移/自动 Flush/调度器/背压全部 ✅；
   定位升级为 **Redis-compatible LSM based Tiered KV Storage Engine**
   （README/AGENT_CONTEXT 已同步）。

## Phase 7 评审结论（2026-08-10）

1. **执行链路正确**：Netty → executeAsync → KeyShardExecutor → ShardRouter →
   ShardWorker → StorageEngine；同键 FIFO、异键并行，符合数据库通用模型。
2. **ResponseSequencer 为最大亮点**：异步执行 + 有序响应交付，等价于
   Netty ChannelPromise / Kafka producer sequence / HTTP/2 stream ordering；
   Redis pipeline 语义未被破坏。
3. **ADR-0023 合理**：`min(16, CPU)` 分片是吞吐/开销的平衡点。
4. **MemTable 256 段选择稳健**：冲突概率降约 4×；未强行上 lock-free
   （lock-free ≠ faster，高竞争 CAS retry 可能更差）——正确工程判断。
5. **Hot Key 治理完整**：检测 → 本地缓存 → 请求合并（10000 请求 → 1 loader），
   解决缓存击穿。
6. **性能达标**：GET 2.6–6.3M（目标 6×）、SET 2.2–4.5M（目标 8×）、
   P99 ≤0.106ms；口径为内存直连，Phase 9 需全链路验证。
7. **Phase 9 基准计划（TD-016）**：A 内存（已有）、B 服务端
   （100 连接 + pipeline 64 + SET/GET mix）、C 生产全链路
   （Client → Netty → ShardExecutor → WAL → MemTable → SSTable）。
8. **技术债确认**：TD-015 全量无锁读暂缓（ABA/回收/可见性）；动态重分片
   （TD-017，Phase 10，需 task migration / routing version / double write）；
   Hot Cache 为 TTL 兜底的事件一致性，未来加 version check（TD-018）。
9. **能力矩阵（19 项全 ✅）**：定位升级为
   **高并发 Redis 协议兼容 LSM-based 冷热分层 KV 存储引擎**
   （README/AGENT_CONTEXT 已同步）。
