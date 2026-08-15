# Phase 62 — P1a Storage Engine Trio

## Context

Optimization Roadmap P1a：leveled compaction（TD-012）、MemTable 轮转
（TD-013）、迁移队列增强（TD-014）。基线：v4.0 完成（M1–M4）。

## Goal

1. ADR-0323/0324/0325 已批准（本阶段）
2. 迁移队列增强：批量 + 准入 + 动态 worker
3. Leveled compaction：level 元数据 + 合并策略 + 读顺序
4. MemTable 轮转：active/immutable + flush 无停顿 + WAL 联动
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 迁移队列 | MigrationScheduler（batch/admission/adjust）+ TierWorkerPool.adjust + 测试 |
| Leveled | storage/cold/LeveledCompaction.java + 测试 |
| MemTable | storage/memory/MemTableManager.java + FlushScheduler 适配 + 测试 |

## Test Plan

- 迁移：批量冷层单表、准入拒绝、worker 水位调整、恢复幂等
- Leveled：合并矩阵（latest/tombstone/TTL）、读顺序、容量触发
- MemTable：rotation、读合并、写不停顿、WAL 恢复重建
- 全量回归 0 failures；新增测试 ≥120

## 验收

- 三 ADR 已批准；Conventional Commit 拆分
- 本地全量回归 0 failures；真实 Runner 门禁 6/6
- 基准：迁移批量吞吐、compaction 读放大、flush 停顿
