# Phase 58 — v4.0 M1 Vector Storage Integration

## Context

v4.0 M1（RFC-0001 / ADR-0319）：向量从内存原型升级为文件持久化闭环
+ mmap 读取 + SQL 混合检索。基线：v3.7.1 已发布，main/develop 门禁
7/7 全绿。

## Goal

1. 向量索引文件格式（VectorIndexFile：magic/version/CRC + 原子写）
2. VectorIndexStore：write / load / rebuild / 损坏检测
3. VectorIndexMmapReader：MappedFile 复用 + BlockCache 缓存
4. SQL 混合检索：SqlIndexRegistry 向量索引类型 + VectorSqlSearch
5. CRUD 持久化 E2E + 性能基准
6. 全量回归 0 failures + 门禁提交

## 交付

| 模块 | 文件 |
| --- | --- |
| 文件格式 | vector/indexfile/VectorIndexFile.java |
| 存储闭环 | vector/indexfile/VectorIndexStore.java |
| mmap 读取 | vector/io/VectorIndexMmapReader.java |
| SQL 接线 | sql/SqlIndexRegistry.java（IndexType 扩展）+ vector/sql/VectorSqlSearch.java |
| 测试 | src/test/.../vector/indexfile/、vector/io/、vector/sql/ |
| 基准 | docs/benchmark/phase58-vector-storage-report.md |

## Test Plan

- VectorIndexFile：roundtrip、CRC 损坏拒绝、版本拒绝、空索引
- VectorIndexStore：write→load 一致、重建、原子写（无半文件）
- VectorIndexMmapReader：mmap 读取与内存检索结果一致、BlockCache 命中
- VectorSqlSearch：标量谓词过滤 + topK、空结果、边界
- SQL 接线：registry 向量注册/查询、planner 提示
- E2E：put→checkpoint→load→search 全链路
- 全量回归 0 failures；新增测试 ≥80

## 验收

- ADR-0319 已批准（本文档引用）
- 本地 `mvn test` 全量 0 failures
- 基准报告输出（写入吞吐 / mmap 读 P50/P99）
- Conventional Commit 拆分提交

## ADR

ADR-0319（v4 M1 Vector Storage Integration）— 本阶段唯一新增。
