# ADR-0309: Operations Closure & GA Baseline

## Status

Accepted

## Context

GA 需要运营资产与完成度判定。

## Decision

采用运营收尾 + GA 基线：

- SLO 报告 / 发布归档 / 审计导出（GaAuditExport）；
- ProductCompletenessBaseline v2（能力终态 + 技术债终态 +
  成品判定）。

## Consequences

优点：运营可交付、判定可评审。

缺点：SLO 跨地域口径封板。

风险：基线需随复审更新。

## Implementation

`io.tieringkv.operations.GaAuditExport`、
`ProductCompletenessBaseline` v2 +
`src/test/java/io/tieringkv/operations/GaOpsClosureTest.java`、
`docs/operations/ga-operations-closure.md`、
`docs/review/product-completeness-baseline-v2.md`。
