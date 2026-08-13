# ADR-0305: Real Runner Final Review & Gate Sealing

## Status

Accepted

## Context

真实 Runner 门禁长期封板（无远程）；GA 需要唯一终态。

## Decision

采用 GateConvergenceV17：

- 终态 CLOSED / SEALED_GA / REGISTERED_RELEASE；
- SEALED_GA：交付物就绪 + 阻塞原因归档 + 复审条件声明；
- 无滚动 defer；封板声明文档 + 决策矩阵测试。

## Consequences

优点：终态唯一、可审计、可复审。

缺点：真实执行仍待环境。

风险：封板结论需随环境可用性更新。

## Implementation

`io.tieringkv.ci.GateConvergenceV17` +
`src/test/java/io/tieringkv/ci/GateConvergenceV17Test.java`、
`docs/deployment/real-runner-final-review.md`。
