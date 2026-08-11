# ADR-0090: Runtime Deployment Architecture

## Status

Accepted

## Context

TD-043：Phase 21 的事务协议已在 TCP 测试覆盖，但容器运行时未托管
Gateway / TransactionParticipant / Metadata；真实部署仍缺完整链路。

## Decision

- `docker-compose.transaction.yml`：
  - node1：Redis Gateway + Region-A Leader + Participant；
  - node2：Region-B Leader + Participant；
  - node3：Metadata Raft + Replica；
- `TxnRuntimeMain`：容器入口，按参数启动 Gateway（Redis RESP）+
  MultiRaftEndpoint + TransactionParticipant + TransactionMetadataService，
  事务 RPC 全部走真实 TCP（RpcTxnTransport），禁止 LocalTxnTransport；
- 端到端验证：SET keyA/keyB → COMMIT → restart node2 → verify；
- 磁盘混沌（ADR-0086 延续）：disk full / readonly fs / corrupt wal /
  slow io 真实注入（关闭 TD-044）。

## Alternatives

1. 进程内 shortcut：无法验证真实网络/容器生命周期。
2. 仅协议测试：缺少运行时证据。

## Consequences

优点：

- 完整生产部署链路与端到端证据；
- 与 Phase 21 协议层解耦，可独立演进。

缺点：

- 容器编排与运维脚本成本。

风险：

- 中；由 EndToEndDistributedTxnTest 与 phase22-chaos-report 验证。

## Implementation

- `deploy/docker-compose.transaction.yml`、`TxnRuntimeMain`；
- `EndToEndDistributedTxnTest`（真实 TCP）；
- `docs/deployment/phase22-runtime-deployment.md`。
