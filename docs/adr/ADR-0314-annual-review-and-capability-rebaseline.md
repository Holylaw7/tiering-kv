# ADR-0314: Annual Review & Capability Rebaseline

## Status

Accepted

## Context

文档/基准/能力矩阵需定期复核。

## Decision

采用年度复核：检查清单（文档/基准/能力/门禁）+ `annual-review.sh`
报告生成。

## Consequences

优点：基线可刷新。

缺点：复核为人工驱动。

风险：过期文档需人工修订。

## Implementation

`docs/operations/annual-review.md`、`scripts/annual-review.sh` +
`src/test/java/io/tieringkv/operations/AnnualReviewTest.java`。
