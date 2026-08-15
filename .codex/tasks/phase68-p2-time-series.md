# Phase 68 — P2 功能深度：时序命令族

## Context

Optimization Roadmap P2 第三交付：时序查询/聚合/下采样。基线：
JSON 路径完成（Phase 67），TIME_SERIES 仅有 ts.add/get/len 基础
读写。

## Goal

1. ADR-0337 已批准（本阶段）
2. TS.RANGE（AGGREGATION 桶聚合 AVG/SUM/MIN/MAX/COUNT/FIRST/LAST +
   COUNT）
3. TS.INCRBY（TIMESTAMP 同刻累加/新刻追加，原子 + TTL 保留）
4. TS.MRANGE（全部 TIME_SERIES 键，按键名排序）
5. TS.REDUCE（全序列聚合，项目扩展）
6. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 命令 | command/TimeSeriesCommand.java（4 命令） |
| 测试 | command/TimeSeriesCommandFamilyTest |
| 注册 | CommandRegistry 扩展注册表 +4（默认 127 不变） |
| 文档 | ADR-0337、command-family-design、RESP2 矩阵、CHANGELOG |

## Test Plan

- RANGE：区间包含/空键/排序/WRONGTYPE/COUNT 限制
- AGGREGATION：AVG/SUM/MIN/MAX/COUNT/FIRST/LAST 桶对齐（含跨桶、
  负时间戳）
- INCRBY：显式 TIMESTAMP 同刻累加/新刻追加、省略 TIMESTAMP、
  非法参数
- MRANGE：多键排序输出、聚合、跳过非 TS 键
- REDUCE：全序列聚合/空序列/非 TS 键
- 全量回归 0 failures；新增测试 ≥25

## 验收

- ADR-0337 已批准；Conventional Commit 拆分
- RedisTimeSeries 桶聚合语义通过（floorDiv 对齐 + 聚合结果）
- 全量回归 0 failures；真实 Runner 门禁 6/6
