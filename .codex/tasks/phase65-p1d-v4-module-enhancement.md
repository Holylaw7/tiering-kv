# Phase 65 — P1d v4 Module Enhancement

## Context

Optimization Roadmap P1d：HNSW 图检索（M1 限制）、复制流水线增强
（M3 限制）。基线：P1c 完成。

## Goal

1. ADR-0332/0333 已批准（本阶段）
2. HnswIndex 图检索（构建/搜索/召回/序列化）+ 基准 P99 <1ms
3. 复制批量发送 + 异步 ack + 水位周期刷盘 + ConflictResolver 抽象
4. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| HNSW | vector/hnsw/HnswIndex.java 重写 + 测试 |
| 复制 | CrossClusterReplicationChannel（batch/async）、Watermark（周期）、ConflictResolver + 测试 |
| 基准 | docs/benchmark/phase65-hnsw-search-report.md |

## Test Plan

- HNSW：构建/搜索/召回率（≥0.9 vs 暴力）、序列化 roundtrip、空索引
- 复制：批量发送/远端批量应用、异步 ack metrics、水位周期刷盘、
  ConflictResolver 接口 + LWW 实现
- 全量回归 0 failures；新增测试 ≥30

## 验收

- 两 ADR 已批准；Conventional Commit 拆分
- 20K × 64 维检索 P99 <1ms（本地基准）
- 全量回归 0 failures；真实 Runner 门禁 6/6
