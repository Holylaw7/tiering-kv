# ADR-0241: Real Runner Gate Convergence v13

## Status

Accepted

## Context

GateConvergenceV12（Phase 46）登记的 Linux Runner / 跨机 / 跨地域门禁
预期在 Phase 47 消除。本阶段执行或如实登记，并归档执行记录与趋势报表。

## Decision

采用收敛表 v13：

- `GateConvergenceV13` 注册表维护每项状态 / 阻塞原因 / 预期消除阶段；
- JVM 级扩展：Phase47ProductionGateTest + EdgeMatrix + 收敛表校验测试；
- 执行记录归档：`RunnerExecutionArchive` 记录可执行项结果（时间/状态/证据）；
- 真实 Runner 项不伪报，未执行项如实登记（预期 Phase 48）。

## Alternatives

1. 等待 Runner 就绪再交付：阻塞整个阶段，不可接受；
2. 声明全部完成：违反「禁止伪报」原则；
3. 仅保留 v12 表：无法体现 Phase 47 的归档与 JVM 进展。

## Consequences

优点：可审计、可跟踪、执行记录可归档。

缺点：真实 Runner 闭环仍可能延后。

风险：登记项持续积累，需在 Phase 48 优先闭环。

## Implementation

`src/main/java/io/tieringkv/ci/GateConvergenceV13.java`、
`src/main/java/io/tieringkv/ci/RunnerExecutionArchive.java` +
`src/test/java/io/tieringkv/ci/GateConvergenceV13Test.java`、
`docs/deployment/gate-convergence-v13.md`。
