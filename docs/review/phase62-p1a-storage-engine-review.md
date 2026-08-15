# Phase 62 Review — P1a Storage Engine Trio

## 总体结论

Optimization Roadmap P1a 完成：迁移队列增强（批量/准入/动态 worker）、
Leveled compaction、Active/Immutable MemTable 轮转 + FlushScheduler
生产接入。全量回归 **14714 tests / 0 failures**（本地），真实 Runner
门禁全绿（build/test/transaction-e2e × main/develop）。

## 交付清单

1. **迁移队列增强**（ADR-0325，TD-014）：submitBatch（有序单表写入 +
  逐条 WAL/内存删除）、maxPending 准入、TierWorkerPool.adjust 动态
  worker（水位滞回）；
2. **Leveled compaction**（ADR-0323，TD-012）：LeveledCompaction 内存
  level 元数据（L0 阈值 + level 容量 → 与下一级合并、L0 新→旧读序）、
  ColdStorageEngine.compactLeveled；
3. **MemTable 轮转**（ADR-0324，TD-013）：MemTableManager（active +
  immutable，写路径 active、读合并新优先、rotate + WAL 段轮转、
  flushOldest、跨表 delete/tombstone、合并迭代器、WAL 恢复重建）；
4. **生产接入**：FlushScheduler manager 模式（rotate + flush 最老
  immutable，写入不停顿），MemTableManager 版本守卫物理删除。

## 测试与门禁

- 新增测试 23 项（迁移 3 + leveled 9 + MemTable 6 + 接入 2 + 兼容回归）；
- 全量回归 14714 / 0 failures / 6 skipped；
- 真实 Runner：build / test / transaction-e2e × main/develop 全绿；
- 修复：JDK setCorePoolSize core<=max 约束、SSTable 批量 key 排序、
  tombstone 跨表覆盖、TierWorkerPool 动态调整顺序。

## 已知限制（如实记录）

- 迁移批量失败回退逐条重试（幂等，吞吐部分损失）；
- LeveledCompaction level 元数据为内存态，重启需重建（表量可控）；
- MemTableManager 的迁移调度仍面向 active 表（immutable 迁移为后续）；
- MemTableManager 迭代器为合并快照（内存收集），超大表占用需评估。

## 后续

- P1b（缓存/淘汰）已完成并归档（phase63）；
- P1c（并发/性能）与 P1d（v4 模块增强）按 optimization-roadmap 推进。
