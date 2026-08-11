# ADR-0087: Transaction Decision Ordering

## Status

Accepted

## Context

Phase 21 的元数据采用“本地日志先落盘 → Raft 提案”顺序，存在 at-least-once
窗口：Raft 失败时本地记录先于共识生效；恢复以本地为准，可能与其他副本
分歧。同时 2PC 流程需要保证“COMMIT 决策先经 Raft 持久化，再令
participant 提交”，禁止先 participant commit 后 metadata commit。

## Decision

- 元数据状态机增加 `decisionIndex`（Raft 日志索引）：PREPARE/COMMIT 命令
  携带提案返回的索引，状态按索引单调推进；
- 持久化顺序改为 Raft-first：`proposer.apply(payload)` 成功后才 apply 状态
  并追加本地镜像日志（镜像仅用于观测，不作为权威）；
- 协调器流程：metadata PREPARE（决策持久化）→ participant commit RPC →
  metadata COMMIT（终态化）；participant 提交必须发生在决策持久化之后；
- 恢复：优先从 Raft 应用日志重建状态（TxnMetadataRaftGroup 提供已应用
  命令列表），本地镜像作为兜底。

## Alternatives

1. 本地日志先落盘：保留 at-least-once 窗口，与其他副本可能分歧。
2. participant 先提交再记录决策：崩溃后可能丢失提交。
3. 单轮 Raft 原子提交全部 participant：需要跨 Region 状态机，复杂度高。

## Consequences

优点：

- 决策先于执行，恢复无幻影/无丢失；
- decisionIndex 提供全局有序恢复依据。

缺点：

- 提案成功后才能 apply，单次 RPC 延迟略有增加；
- participant 已提交而元数据未终态化的窗口仍存在（由幂等恢复兜底）。

风险：

- 低；由 MetadataReorderTest / DuplicateCommitTest /
  CrashBetweenDecisionTest 验证。

## Implementation

- `transaction/metadata`：TxnMetaEntry.decisionIndex、Raft-first propose、
  recoverFromRaft；
- `transaction/router`：PREPARE 决策持久化后再 commit participants。
