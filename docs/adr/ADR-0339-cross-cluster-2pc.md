# ADR-0339: Cross-Cluster Two-Phase Commit

## Status

Accepted

## Context

P2 功能深度最大项：跨集群事务。现有能力：集群内分布式事务
（Percolator 2PC + GeoTransactionCoordinator 决策先行）与跨集群
复制（CrossClusterReplicationChannel + LWW 冲突收敛）。缺：把
事务阶段事件经复制通道发送到另一集群并保证决策可恢复。

## Decision

- 扩展 `ChangeEvent.EventType`：追加 TXN_PREPARE / TXN_ROLLBACK
  （枚举末尾追加，既有 ordinal 0-3 冻结，线格式兼容）；
- `CrossClusterTxnParticipant`：作为复制通道 consumer——TXN_PREPARE
  阶段暂存（不落盘）；TXN_COMMIT 按 LWW 决策应用暂存 mutation
  （commitTS 参与冲突收敛，同 seq 重放幂等）；TXN_ROLLBACK 丢弃
  暂存；COMMIT 无 PREPARE（恢复重放）直接按事件应用；
- `CrossClusterTxnCoordinator`：按 `clusterOf(key)` 分组 → 向各
  集群发送 TXN_PREPARE（sendBatch 等待 ack）→ 全部成功后决策先行
  落盘（`CrossClusterDecisionLog`，携带 mutations 供恢复重放）→
  发送 TXN_COMMIT；任一 PREPARE 失败则决策 ROLLBACK 并通知全部；
- `CrossClusterDecisionLog`：'CCDC' magic + CRC32C 追加日志，
  payload 含 txnId/decision/commitTS/mutations（GeoDecisionLog
  仅含决策，恢复无法重放）；`recover()` 对 COMMIT 决策按 mutations
  重发 COMMIT（参与者幂等）；
- 冲突收敛：复用 LwwConflictResolver（timestamp + clusterId +
  region/seq 幂等），无需新收敛协议。

## Alternatives

1. 直接扩展 RPC 事务协议跨集群：侵入 Raft/元数据层，改动大；
2. 仅 CDC 最终一致：无原子性。

## Consequences

优点：复用复制通道与 LWW，决策可恢复，参与者无持久化改动。

缺点：PREPARE 为暂存式（未加锁）；跨集群无事务级隔离（LWW 收敛）。

风险：枚举扩展需冻结旧 ordinal——已按末尾追加并测试线格式。

## Implementation

`transaction/cross/`：CrossClusterDecision/DecisionLog、
CrossClusterTxnCoordinator/Participant；ChangeEvent 枚举追加；
`CrossClusterTransactionTest`（双 endpoint E2E + 冲突 + 恢复）。
