# ADR-0306: Jepsen-style Harness Externalization

## Status

Accepted

## Context

线性化验证内嵌测试，无法独立进程运行。

## Decision

采用独立 harness：

- 历史生成器（随机/确定性）+ 并发客户端（线程操作）；
- 结果校验器（LinearizabilityChecker 接线）；
- CLI 入口输出验证报告；网络分区注入接口预留。

## Consequences

优点：可独立运行、可复现、可集成 CI。

缺点：进程内客户端（非独立进程集群）。

风险：分区注入真实化留后续。

## Implementation

`io.tieringkv.distributed.harness.*` +
`src/test/java/io/tieringkv/distributed/VerificationHarnessTest.java`、
`docs/distributed/jepsen-harness.md`。
