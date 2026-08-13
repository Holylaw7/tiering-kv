# ADR-0265: Real Runner Gate Final Disposition v16

## Status

Accepted

## Context

真实环境门禁（TD-048/049、K8S-001、REL-001、BM-001/002、TD-076 等）
从 Phase 25 起多轮"登记待执行"，v15 仍保留"预期消除阶段"滚动字段，
导致门禁永远有下一轮。

## Decision

收敛表 v16 取消滚动 defer：

- 每项门禁唯一终态：CLOSED / ENV_BLOCKED_FINAL /
  REGISTERED_RELEASE；
- `GateConvergenceV16` 移除"预期消除阶段"，改为"终态理由 + 封板
  阶段"；
- 可执行项实际执行并归档证据；环境阻塞项正式封板；
- 任何门禁不再标注"待下一 Phase"。

## Alternatives

1. 继续滚动登记：门禁永远不闭合；
2. 声明全部完成：违反禁止伪报；
3. 删除门禁记录：掩盖事实。

## Consequences

优点：门禁可审计、终态唯一、封板可追溯。

缺点：环境阻塞项正式封板后，需要真实 Runner 才能复审。

风险：封板结论需要与真实环境可用性保持同步更新。

## Implementation

`io.tieringkv.ci.GateConvergenceV16`、
`src/test/java/io/tieringkv/ci/GateConvergenceV16Test.java`、
`docs/deployment/gate-convergence-v16.md`。
