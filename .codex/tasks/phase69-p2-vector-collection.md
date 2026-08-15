# Phase 69 — P2 功能深度：向量多集合命名空间

## Context

Optimization Roadmap P2 第四交付：向量多集合命名空间 + 自动
checkpoint + 集合感知 SQL 混合检索。基线：时序命令族完成（Phase
68），向量仅有单 VectorIndexStore + 4 命令。

## Goal

1. ADR-0338 已批准（本阶段）
2. VectorCollectionRegistry（集合隔离 + dirty 跟踪 + 原子 checkpoint
   + loadAll + 自动 checkpoint）
3. VECTOR.ADD/SEARCH/DEL/LEN 支持 `COLLECTION <name>` 前缀；
   VECTOR.LIST / VECTOR.DROP / VECTOR.CHECKPOINT
4. VectorSqlSearch 集合感知重载（bindCollection + 注册表解析）
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 注册表 | vector/collection/VectorCollectionRegistry.java |
| 命令 | command/VectorCommand.java（集合前缀 + LIST/DROP/CHECKPOINT） |
| SQL | vector/sql/VectorSqlSearch.java（集合重载） |
| 测试 | vector/collection/VectorCollectionTest + VectorSqlSearchTest 扩展 |
| 文档 | ADR-0338、command-family-design、RESP2 矩阵、CHANGELOG |

## Test Plan

- 集合隔离：同 id 不同集合互不影响；默认集合兼容既有命令
- COLLECTION 前缀：ADD/SEARCH/DEL/LEN；自动创建；缺失集合搜索空
- LIST 排序计数、DROP 删除、CHECKPOINT 落盘/未配置错误
- 自动 checkpoint：定时刷脏 + close 兜底 + loadAll 恢复
- SQL：bindCollection 后从注册表检索 + 谓词过滤 + 维度校验 +
  缺失集合错误
- 全量回归 0 failures；新增测试 ≥25

## 验收

- ADR-0338 已批准；Conventional Commit 拆分
- 自动 checkpoint 文件可被 loadAll 恢复（原子写 + CRC）
- 全量回归 0 failures；真实 Runner 门禁 6/6
