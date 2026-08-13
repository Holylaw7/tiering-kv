# ADR-0308: Multi-Cluster Federation Consistency

## Status

Accepted

## Context

跨集群一致性只有单体 CRDT 测试，无联邦矩阵。

## Decision

采用 `FederationConsistencyVerifier`：

- 双集群 VersionVector 同步模拟；
- 冲突率/收敛时间矩阵；
- 环回抑制与合并语义复用既有 CRDT。

## Consequences

优点：联邦一致性可测。

缺点：进程内模拟，非真实跨集群。

风险：跨地域基准待 Runner。

## Implementation

`io.tieringkv.distributed.FederationConsistencyVerifier` +
`src/test/java/io/tieringkv/distributed/FederationConsistencyTest.java`、
`docs/distributed/multi-cluster-federation.md`。
