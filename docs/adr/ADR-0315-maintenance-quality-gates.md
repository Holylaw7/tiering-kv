# ADR-0315: Maintenance Quality Gates

## Status

Accepted

## Context

维护期质量不得降级。

## Decision

采用维护门禁：回归 + 覆盖率 + 静态分析 + 依赖漏洞；
`maintenance-gates.sh` 一键运行。

## Consequences

优点：质量可证明。

缺点：门禁运行耗时。

风险：依赖漏洞需人工评估。

## Implementation

`scripts/maintenance-gates.sh` +
`src/test/java/io/tieringkv/operations/MaintenanceGatesTest.java`。
