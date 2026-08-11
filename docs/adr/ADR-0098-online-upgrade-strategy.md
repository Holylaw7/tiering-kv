# ADR-0098: Online Upgrade Strategy

## Status

Accepted

## Context

发布新版本必须无停止服务、Raft quorum 保持、事务不丢失。

## Decision

- 滚动升级：node1 → wait catchup → node2 → node3；
- 每节点升级期间 quorum 保持（3 节点集群逐节点替换）；
- 升级完成后验证日志收敛与已提交事务可读；
- 版本兼容：不修改 Raft 日志格式与事务协议（v1 日志 v2 可读）。

## Alternatives

1. 全量停机升级：不可接受。
2. 蓝绿双集群：成本高。

## Consequences

优点：无停机、无丢失。风险：中；由 RollingUpgradeTest 验证。

## Implementation

- `runtime/`：UpgradeCoordinator（升级编排）；
- 测试：RollingUpgradeTest。
