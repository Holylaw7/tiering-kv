# ADR-0298: Raft Edge Case Validation

## Status

Accepted

## Context

Raft 边角（snapshot/pre-vote/成员变更/截断恢复/空心跳）缺少系统性
验证矩阵。

## Decision

采用验证矩阵（只测不改）：

- snapshot 安装与重启重放；
- pre-vote 防脑裂；
- 成员变更 add/remove；
- 截断日志恢复；空心跳不提交；滞后副本回填；
- 发现缺陷必须走 ADR 修复流程。

## Alternatives

1. 不改不测：风险隐藏；
2. 边测边改：破坏既有 safety 基线。

## Consequences

优点：边角可回归、缺陷可审计。

缺点：部分场景需要较长超时。

风险：真实网络分区仍待 Runner。

## Implementation

`src/test/java/io/tieringkv/distributed/RaftEdgeCaseTest.java` +
`docs/distributed/raft-edge-cases.md`。
