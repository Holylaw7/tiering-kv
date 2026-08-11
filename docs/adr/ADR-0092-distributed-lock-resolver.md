# ADR-0092: Distributed Lock Resolver

## Status

Accepted

## Context

Phase 22 的 LockResolver 为协调器本地实现；跨 Region 场景下，读取方发现
锁后需要跨节点向 primary 查询状态并解析，本地无法完成。

## Decision

- 新增事务 RPC：`CHECK_TXN_STATUS` / `RESOLVE_LOCK` / `HEARTBEAT_LOCK`；
- `TransactionParticipant` 提供幂等 handler：
  - CHECK_TXN_STATUS：返回本 participant 记录的事务状态；
  - RESOLVE_LOCK：依据本地状态补完 commit 或回滚（幂等）；
  - HEARTBEAT_LOCK：续约本 participant 上的锁；
- `LockResolverClient`：跨 Region 通过 RPC 解析 primary/secondary 锁；
- 协调器 LockResolver 在本地无权威信息时回退到 RPC 解析。

## Alternatives

1. 仅本地解析：无法处理跨 Region orphan。
2. 全局锁服务：新组件，复杂度高。

## Consequences

优点：

- 跨 Region 锁解析闭环；
- 复用幂等 participant 语义。

缺点：

- 每把锁解析 1–2 次 RPC。

风险：

- 低；由 LockRpcTest / PrimaryCrashResolveTest 验证。

## Implementation

- `cluster/rpc`：新增 TXN_CHECK_STATUS / TXN_RESOLVE_LOCK /
  TXN_HEARTBEAT_LOCK 消息；
- `transaction/`：participant handler + LockResolverClient；
- 测试：LockRpcTest。
