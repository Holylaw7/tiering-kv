# ADR-0216: TSO Cluster Service

## Status

Accepted

## Context

事务时间戳由协调器本地生成；缺少全局单调、批量分配与恢复的 TSO 服务。

## Decision

1. `transaction/tso/TsoService`：批量分配 + 单调推进 + 恢复不回退；
2. 与 resolved-ts / 事务协调器联动；
3. 验收：分配矩阵 + 单调性 + 恢复不回退。

## Alternatives

1. 本地时间戳：跨节点不单调；
2. 单机 TSO：单点风险。

## Consequences

优点：全局单调时间戳。

缺点：需要集群服务。

风险：恢复回退由持久化兜底。

## Implementation

代码影响范围：`transaction/tso/` + 测试 +
`docs/transaction/tso-cluster.md`。
