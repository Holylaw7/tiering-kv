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
