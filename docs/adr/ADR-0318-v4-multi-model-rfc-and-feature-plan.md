# ADR-0318: v4 Multi-Model RFC & Feature Plan

## Status

Accepted

## Context

RFC-0001（v4.0 Multi-Model & Production Vector Path）提出后需批准
并规划特性分支。

## Decision

RFC 获批后：

- 创建 `feature/v4-multi-model`（sql/vector/storage types）；
- 阶段拆分：SQL 索引接线 → 向量存储接入 → 多模型编码 →
  多集群复制接线；
- 每阶段 ADR + 全量回归 0 failures。

2026-08-14 批准：RFC-0001 Approved；`feature/v4-multi-model` 已创建；
阶段一交付 `SqlIndexRegistry`（SQL 索引接线脚手架）。

## Alternatives

1. 单一大分支：评审困难；
2. 先做 SQL 或向量：顺序取舍。

## Consequences

优点：方向可评审、增量可交付。

缺点：特性开发成本高。

风险：与维护模式资源竞争。

## Implementation

`docs/planning/rfc-0001-v4-multi-model.md`、
`feature/v4-multi-model`（获批后创建）。
