# ADR-0187: Remote Materialization Auto-Tiering

## Status

Accepted

## Context

远端物化视图统一存储，无热度分层；热点与冷数据同层，存储成本高。

## Decision

1. `datamesh/AutoTierManager`：访问统计 → 分层决策（HOT/WARM/COLD）
   + 迁移策略；
2. 分层保持 stale 语义与主权约束；
3. 与 MaterializedViewLifecycle 联动；
4. 验收：热度矩阵 → 分层、迁移正确、主权拒绝。

## Alternatives

1. 单层存储：冷数据浪费；
2. 人工分层：维护成本高。

## Consequences

优点：存储成本优化。

缺点：分层迁移需配置。

风险：误分层由热度阈值矩阵兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/auto-tiering.md`。
