# ADR-0096: Production Runtime Lifecycle

## Status

Accepted

## Context

容器运行时缺少健康检查与优雅停机：无法探活、无法安全排空事务。

## Decision

- 健康端点：`/health`（进程存活）、`/readiness`（raft leader/日志追平、
  pending txn、lock count）、`/liveness`（自检通过）；
- 优雅停机：SIGTERM → stop accept → 排空 inflight txn（有界等待）→
  flush raft → close storage；
- `RuntimeHealth` 聚合运行时快照；`GracefulShutdown` 封装停机顺序。

## Alternatives

1. 仅进程存活检查：无法判断是否可服务。
2. 直接 kill：丢事务。

## Consequences

优点：K8s/容器探活与滚动升级前置条件。风险：低。

## Implementation

- `runtime/`：RuntimeHealth、GracefulShutdown；
- 测试：GracefulShutdownTest、RuntimeHealthTest。
