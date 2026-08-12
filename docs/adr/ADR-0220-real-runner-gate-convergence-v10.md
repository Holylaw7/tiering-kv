# ADR-0220: Real Runner Gate Convergence v10

## Status

Accepted

## Context

Phase 25–43 累计登记的 Linux Runner / 跨机 / 跨地域门禁（TD-048/049、
K8S-001、REL-001、BM-001/002、TD-051/054/059/060/063/066/069/072/
075/078）交付物已就绪，但真实执行仍受环境限制。Phase 44 需要把收敛表
推进到 v10，并继续完善 JVM 级可执行项。

## Decision

采用「可执行项全绿 + 未执行项精确登记」的收敛模型：

- `GateConvergenceV10` 注册表维护每项状态 / 阻塞原因 / 预期消除阶段；
- JVM 级扩展：Phase44ProductionGateTest + EdgeMatrix + 收敛表校验测试；
- 真实 Runner 项不伪报，阻塞原因与预期消除阶段如实登记；
- TD-076/079/080 已关闭方向不再重复登记为待办。

## Alternatives

1. 等待 Runner 就绪再交付：阻塞整个阶段，不可接受；
2. 声明全部完成：违反“禁止伪报”原则；
3. 仅保留 v9 表：无法体现 Phase 44 的 JVM 级进展。

## Consequences

优点：可审计、可跟踪、无伪报风险。

缺点：真实 Runner 闭环仍延后。

风险：登记项若长期不执行会积累，需在 Phase 45 优先闭环。

## Implementation

`src/main/java/io/tieringkv/ci/GateConvergenceV10.java`、
`src/test/java/io/tieringkv/ci/GateConvergenceV10Test.java`、
`docs/deployment/gate-convergence-v10.md`。
