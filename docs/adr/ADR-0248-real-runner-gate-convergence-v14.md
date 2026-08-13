# ADR-0248: Real Runner Gate Convergence v14

## Status

Accepted

## Context

GateConvergenceV13（Phase 47）登记的 Linux Runner / 跨机 / 跨地域门禁
预期在 Phase 48 消除。本阶段执行或如实登记，全量闭环 + 发布记录归档。

## Decision

采用收敛表 v14：

- `GateConvergenceV14` 注册表维护每项状态 / 阻塞原因 / 预期消除阶段；
- JVM 级扩展：Phase48ProductionGateTest + EdgeMatrix + 收敛表校验测试；
- 发布记录归档：`ReleaseRecordArchive` 记录可执行项结果与发布记录；
- 真实 Runner 项不伪报，未执行项如实登记（预期 Phase 49）。

## Alternatives

1. 等待 Runner 就绪再交付：阻塞整个阶段，不可接受；
2. 声明全部完成：违反「禁止伪报」原则；
3. 仅保留 v13 表：无法体现 Phase 48 的发布归档与 JVM 进展。

## Consequences

优点：可审计、可跟踪、发布记录可归档。

缺点：真实 Runner 闭环仍可能延后。

风险：登记项持续积累，需在 Phase 49 优先闭环。

## Implementation

`src/main/java/io/tieringkv/ci/GateConvergenceV14.java`、
`src/main/java/io/tieringkv/ci/ReleaseRecordArchive.java` +
`src/test/java/io/tieringkv/ci/GateConvergenceV14Test.java`、
`docs/deployment/gate-convergence-v14.md`。
