# ADR-0255: Real Runner Gate Convergence v15

## Status

Accepted

## Context

GateConvergenceV14（Phase 48）登记的 Linux Runner / 跨机 / 跨地域门禁
预期在 Phase 49 消除。本阶段将可执行项全量闭环，未执行项给出精确终态
（环境阻塞登记），并提供跨地域趋势报表与闭环归档。

## Decision

采用收敛表 v15 + 闭环归档：

- `GateConvergenceV15` 注册表维护每项状态 / 阻塞原因 / 预期消除阶段 /
  最终处置（CLOSED_JVM / ENV_BLOCKED / REGISTERED_RELEASE）；
- `RunnerClosureArchive` 记录快照、趋势点、告警历史并生成归档报表；
- 可执行项全绿，未执行项如实登记，禁止伪报完成。

## Alternatives

1. 等待 Runner 就绪再交付：阻塞整个阶段，不可接受；
2. 声明全部完成：违反「禁止伪报」原则；
3. 仅保留 v14 表：无法体现闭环归档与趋势报表。

## Consequences

优点：门禁可审计、趋势可追踪、归档可导出。

缺点：真实 Runner 项仍受环境限制，需在 Phase 50+ 真实执行补证。

风险：登记项若不再验证将失去说服力，需在后续阶段实际运行。

## Implementation

`src/main/java/io/tieringkv/ci/GateConvergenceV15.java`、
`src/main/java/io/tieringkv/ci/RunnerClosureArchive.java` +
`src/test/java/io/tieringkv/ci/GateConvergenceV15Test.java`、
`docs/deployment/gate-convergence-v15.md`。
