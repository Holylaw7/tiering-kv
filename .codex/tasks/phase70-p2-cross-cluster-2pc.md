# Phase 70 — P2 功能深度：跨集群 2PC

## Context

Optimization Roadmap P2 最后一项：跨集群事务。基线：向量多集合
完成（Phase 69）；已有集群内 2PC、跨集群复制通道与 LWW 收敛。

## Goal

1. ADR-0339 已批准（本阶段）
2. ChangeEvent 追加 TXN_PREPARE/TXN_ROLLBACK（旧 ordinal 冻结）
3. CrossClusterTxnParticipant（暂存/提交 LWW 应用/回滚丢弃/重放幂等）
4. CrossClusterTxnCoordinator（PREPARE 全成 → 决策落盘 → COMMIT；
   失败 ROLLBACK）
5. CrossClusterDecisionLog（含 mutations，recover 重发 COMMIT）
6. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 事件 | cdc/ChangeEvent.java（枚举追加） |
| 决策 | transaction/cross/CrossClusterDecision + DecisionLog |
| 协调器 | transaction/cross/CrossClusterTxnCoordinator |
| 参与者 | transaction/cross/CrossClusterTxnParticipant |
| 测试 | transaction/cross/CrossClusterTransactionTest |
| 文档 | ADR-0339、command-family-design、RESP2 矩阵、CHANGELOG |

## Test Plan

- 双 endpoint E2E：跨两集群提交成功，双方落盘；决策 COMMIT
- PREPARE 失败 → 全回滚 + 决策 ROLLBACK + 存储无残留
- 并发冲突：高 commitTS 收敛；同 seq 重放幂等
- 恢复：决策日志 COMMIT → recover 重发 → 参与者幂等应用
- ROLLBACK 丢弃暂存；COMMIT 无 PREPARE 直接应用
- 决策日志 roundtrip（含 mutations + CRC）
- 全量回归 0 failures；新增测试 ≥25

## 验收

- ADR-0339 已批准；Conventional Commit 拆分
- 跨集群事务 E2E 通过（真实 TCP endpoint）
- 全量回归 0 failures；真实 Runner 门禁 6/6
