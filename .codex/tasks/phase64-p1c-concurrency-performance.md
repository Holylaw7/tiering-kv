# Phase 64 — P1c Concurrency & Performance

## Context

Optimization Roadmap P1c：WAL 并行恢复（TD-007）、request→response
对象数优化（TD-020/021）、JDK 21 虚拟线程 POC（TD-002）。基线：
P1a/P1b 完成并归档。

## Goal

1. ADR-0329/0330/0331 已批准（本阶段）
2. ParallelRecoveryManager + 测试
3. 命令路径对象分配审计 + 针对性复用 + JFR 验收
4. GatewayRuntime 虚拟线程 POC + 基准报告
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| WAL | storage/wal/ParallelRecoveryManager.java + 测试 |
| 对象优化 | 命令路径复用 + JFR 基线 |
| VT POC | GatewayRuntime VT 模式 + 报告 |

## Test Plan

- WAL 并行：多段恢复一致、损坏段截断、TTL 语义、与串行结果等价
- 对象优化：功能回归（协议/命令不变）
- VT：连接规模/延迟对比（1k/10k）
- 全量回归 0 failures；新增测试 ≥20

## 验收

- 三 ADR 已批准；Conventional Commit 拆分
- 本地全量回归 0 failures；真实 Runner 门禁 6/6
- JFR allocation 基线 + VT 基准报告
