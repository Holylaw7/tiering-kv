# ADR-0227: Real Runner Gate Convergence v11

## Status

Accepted

## Context

GateConvergenceV10（Phase 44）登记的 Linux Runner / 跨机 / 跨地域门禁
预期在 Phase 45 消除。本阶段继续推进真实执行，同时保持「可执行项全绿 +
未执行项精确登记」的收敛模型。

## Decision

采用收敛表 v11：

- `GateConvergenceV11` 注册表维护每项状态 / 阻塞原因 / 预期消除阶段；
- JVM 级扩展：Phase45ProductionGateTest + EdgeMatrix + 收敛表校验测试；
- 真实 Runner 项不伪报，未执行项如实登记（预期 Phase 46）；
- TD-076/079/080 已关闭方向不再重复登记为待办。

## Alternatives

1. 等待 Runner 就绪再交付：阻塞整个阶段，不可接受；
2. 声明全部完成：违反「禁止伪报」原则；
3. 仅保留 v10 表：无法体现 Phase 45 的 JVM 级进展。

## Consequences

优点：可审计、可跟踪、无伪报风险。

缺点：真实 Runner 闭环仍可能延后。

风险：登记项持续积累，需在 Phase 46 优先闭环。

## Implementation

`src/main/java/io/tieringkv/ci/GateConvergenceV11.java`、
`src/test/java/io/tieringkv/ci/GateConvergenceV11Test.java`、
`docs/deployment/gate-convergence-v11.md`。
