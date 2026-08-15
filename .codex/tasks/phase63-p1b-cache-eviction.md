# Phase 63 — P1b Cache & Eviction Optimization

## Context

Optimization Roadmap P1b：ARC byte 口径（TD-005）、Segment LFU + Async
Buffer（TD-006）、HotCache version check（TD-018）。基线：P1a 完成。

## Goal

1. ADR-0326/0327/0328 已批准（本阶段）
2. ARCPolicy 字节容量模式 + 测试
3. SegmentLFUPolicy（分段 + 异步缓冲）+ 测试
4. StorageEngine.versionOf + HotKeyReadCache 版本校验 + 测试
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| ARC | storage/cache/ARCPolicy.java（+byte 模式）+ 测试 |
| LFU | storage/cache/SegmentLFUPolicy.java + 测试 |
| HotCache | StorageEngine.versionOf + HotKeyReadCache + 测试 |

## Test Plan

- ARC byte：字节淘汰、容量边界、ghost 语义保持、entry 模式兼容
- SegmentLFU：分段更新、drain 合并、候选正确性、DELETE/EVICT
- HotCache：版本新鲜命中、版本变化刷新、TTL 兜底、写失效
- 全量回归 0 failures；新增测试 ≥40

## 验收

- 三 ADR 已批准；Conventional Commit 拆分
- 本地全量回归 0 failures；真实 Runner 门禁 6/6
