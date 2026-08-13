# ADR-0312: Real Runner Review Execution Pack

## Status

Accepted

## Context

SEALED_GA 门禁需要环境可用时的可执行复审路径。

## Decision

采用复审执行包：

- 执行清单（门禁逐项 + 脚本引用 + 证据模板）；
- `scripts/runner-review.sh`：执行 + 证据归档；
- 复审决策矩阵测试全绿。

## Consequences

优点：SEALED_GA → CLOSED 可执行可留证。

缺点：执行仍需真实 Runner。

风险：环境差异需记录。

## Implementation

`docs/deployment/runner-review-execution-pack.md`、
`scripts/runner-review.sh` +
`src/test/java/io/tieringkv/operations/RunnerReviewPackTest.java`。
