# ADR-0321: v4 M3 Multi-Cluster Replication Wiring

## Status

Accepted

## Context

v4.0 M3（docs/planning/v4-roadmap.md）：把"联邦一致性验证器（模拟）"
升级为"真实跨集群复制接线"。现状：

- `FederationConsistencyVerifier`（ADR-0308）：版本向量 + LWW 的内存
  模拟，输出冲突率/收敛时间，未接真实数据路径；
- `ReplicationPipeline` / `ReplicaSink`：单集群内多地域内存投递；
- `ChangeEvent` / `CdcLog`（ADR-0105）：CDC 事件已序号化、可重放、
  幂等，可作为复制日志；
- `MultiRaftEndpoint` RPC：已有 TCP 传输、认证、超时重试
  （ADR-0058），消息类型到 PUBSUB(33)；
- 冲突检测已有 `ConflictDetector`（主地域优先），无写冲突决策。

## Decision

### 1. 跨集群通道：复用 MultiRaftEndpoint RPC

- `RpcMessageType` 增加 `REPLICATION(34)` / `REPLICATION_RESPONSE(35)`；
- `CrossClusterReplicationChannel`：源端 `send(ChangeEvent)` 经
  `callTxn(remote, "replication", REPLICATION, payload)` 发送；目标端
  注册 `replication` handler 解码并消费；
- 事件编码 `ReplicationEventCodec`：字段定长 + 长度前缀 + CRC32C，
  与 WAL/索引文件校验惯例一致；
- 不新建 TCP 栈：复用 RPC 的认证/限流/超时，additive 不碰 Raft/
  MVCC/事务状态机。

### 2. 冲突策略：LWW（timestamp + cluster id）

- 第一代冲突决策：`LwwConflictResolver`——高 `timestamp` 胜；同
  timestamp 按源 cluster id 字典序胜；DELETE 视为带时间戳的事件；
- 幂等：按 `(regionId, seq)` 记录已应用水位，重放安全；
- CRDT（多值/无主冲突）留 M3 后续演进，接口预留 `ConflictResolver`
  抽象。

### 3. 目标端应用

- `CrossClusterSink`：接收事件 → LWW 决策 → 应用到本地
  `StorageEngine`（PUT/DELETE）；被裁决丢弃的事件不落盘；
- 与 `ReplicaSink` 兼容：可被现有 `ReplicationPipeline` 串联
  （本集群 → 跨集群通道 → 对端集群）。

### 4. 一致性验证接线

- 真实复制路径 E2E 后，将事件喂入 `FederationConsistencyVerifier`
  记录冲突/收敛，验证"模拟结论 ≈ 真实实现"；
- 验收口径：跨集群单写一致、并发双写按 LWW 收敛、重复事件幂等、
  分区后恢复复制无重复丢失（幂等水位保证）。

## Alternatives

1. 独立 TCP 复制通道：重复实现认证/超时/限流，与 RPC 栈分叉；
2. CRDT 先行：实现成本高，M3 无冲突收敛语义即可满足；
3. 仅扩展内存 ReplicaSink：不解决真实跨机/跨集群问题。

## Consequences

优点：

- 复用既有 RPC 安全与传输，事件可重放幂等；
- LWW 与现有 `FederationConsistencyVerifier` 语义一致，可对照验证。

缺点：

- LWW 非 CRDT：并发写丢失低 timestamp 写（如实记录，M3 后演进）；
- 目标端水位为内存态，跨重启续传需持久化（M4/后续）。

风险：

- 复制事件与本地事务并发应用需串行化（目标端单消费者）；
- REPLICATION 消息类型扩展需全链路（客户端/服务端/响应映射）同步。

## Implementation

```text
src/main/java/io/tieringkv/replication/cross/
  ReplicationEventCodec.java
  LwwConflictResolver.java
  CrossClusterSink.java
  CrossClusterReplicationChannel.java
src/main/java/io/tieringkv/cluster/rpc/RpcMessageType.java（+34/35）
```

测试：codec roundtrip / 损坏拒绝 / LWW 决策矩阵 / 跨集群 E2E
（双 endpoint + StorageEngine：单写一致、双写收敛、重复幂等）。
基准：复制事件吞吐（M3 报告）。

关联：docs/planning/v4-roadmap.md（M3 状态）、
.codex/tasks/phase60-v4-m3-multi-cluster-replication.md。
