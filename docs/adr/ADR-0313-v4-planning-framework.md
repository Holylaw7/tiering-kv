# ADR-0313: v4.0 Planning Framework

## Status

Accepted

## Context

v4.0 需要可评审的规划机制。

## Decision

采用规划框架：路线图（多模型/多集群/云原生）+ RFC 模板 +
ADR 预研清单；规划评审矩阵测试。

## Consequences

优点：方向可评审、变更可追溯。

缺点：特性开发仍待批准。

风险：范围膨胀需护栏。

## Implementation

`docs/planning/v4-roadmap.md`、`docs/planning/rfc-template.md` +
`src/test/java/io/tieringkv/operations/V4PlanningTest.java`。
