# Phase 22 运行时部署

## 拓扑

```text
node1 : Redis Gateway + Region-A Leader + Participant
node2 : Region-B Leader + Participant
node3 : Metadata Raft + Replica
```

## 链路

```text
Client → Gateway(Redis RESP) → DistributedTxnRouter
  → RpcTxnTransport(TCP) → TransactionParticipant(node1/node2)
  → TransactionMetadataService(node3 Raft)
```

所有事务 RPC 走真实 TCP（禁止 LocalTxnTransport）。

## 启动

```bash
docker compose -f deploy/docker-compose.transaction.yml up -d
```

## 验证

- EndToEndDistributedTxnTest：SET keyA/keyB → COMMIT → restart node2 →
  verify；
- Phase22RuntimeTest 覆盖 TCP 单区/多区/重启/心跳/锁解析。

## 当前状态

- TCP 端到端已在 JVM 内多端点验证（真实 TCP）；
- 容器运行时托管 Gateway/Participant 的完整编排（TxnRuntimeMain +
  compose.transaction）规划于 Phase 23（TD-043 延续）。
