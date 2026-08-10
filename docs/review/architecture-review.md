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
