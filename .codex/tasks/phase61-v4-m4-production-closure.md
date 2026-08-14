# Phase 61 — v4.0 M4 Production Closure

## Context

v4.0 M4（RFC-0001 / ADR-0322）：生产收口。基线：v4.0 M1/M2/M3 完成。

## Goal

1. CapacityModel：吞吐/延迟/内存/磁盘四维可计算模型
2. 冷/热性能基线：三级基准脚本 + cold-cache 口径
3. Jepsen 外部化：分区/网络注入脚本 + Runner job
4. Operator 完整化：状态机（备份/恢复/滚动升级/多集群）
5. GA 门禁 7/7 ×2 + 报告归档

## 交付

| 模块 | 文件 |
| --- | --- |
| 容量模型 | capacity/CapacityModel.java + 测试 |
| 基准脚本 | scripts/cold-cache-bench.sh、benchmark 增强 |
| Jepsen | scripts/jepsen-run.sh + workflow job |
| Operator | operator 状态机扩展 + 测试 |
| 文档 | docs/benchmark/phase61-production-closure-report.md |

## Test Plan

- CapacityModel：输入校验、四维计算、边界（0 输入/超大值）
- Operator 状态机：Provisioning→Ready→Upgrading→BackingUp/Restoring
  转换矩阵
- 基准脚本：dry-run 输出合法、exit code 语义
- 全量回归 0 failures；新增测试 ≥60

## 验收

- ADR-0322 已批准（本文档引用）
- 本地全量回归 0 failures；真实 Runner 门禁 7/7 ×2
- Jepsen 报告 + 容量模型报告归档
- Conventional Commit 拆分提交
