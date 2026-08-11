# ADR-0093: Production Runtime Deployment

## Status

Accepted

## Context

TD-043：事务协议与 TCP 端到端已就绪，但容器运行时未托管
Gateway / Coordinator / Participant / Metadata，缺少生产部署闭环。

## Decision

- `runtime/` 包：TxnRuntimeMain + GatewayRuntime / CoordinatorRuntime /
  ParticipantRuntime / MetadataRuntime，按 `--role` 启动独立 JVM；
- 全链路 TCP：Gateway → Coordinator → Participant → Metadata Raft，
  禁止 LocalTxnTransport；
- `deploy/docker-compose.transaction.yml` + `docker/Dockerfile.runtime`：
  独立 volume / 独立 network namespace / 独立日志目录；
- 配置：`--node-id --role --region-id --raft-group --rpc-port
  --metrics-port`；
- 验证：ContainerTransactionRuntimeTest 覆盖 gateway/coordinator/
  participant/metadata leader 重启。

## Alternatives

1. 单 JVM 多角色：无法验证网络/生命周期隔离。
2. 进程内 shortcut：与 Phase 21 相同缺陷。

## Consequences

优点：

- 生产可部署、可独立重启；
- 关闭 TD-043。

缺点：

- 编排与运维成本。

风险：

- 中；由 ContainerTransactionRuntimeTest 验证。

## Implementation

- `runtime/`、`deploy/`、`docker/`；
- 测试：ContainerTransactionRuntimeTest。
