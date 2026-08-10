# ADR-0036: Metadata Service Design

## Status

Accepted

## Context

集群需要可靠维护 `shard → node group → leader`。候选：

- ZooKeeper 风格：外部依赖 + 运维成本；
- **Raft Metadata**：自研 Raft 管理元数据，与数据复制共用模型；
- 静态配置：无故障转移能力。

## Decision

采用 **Raft 化元数据服务**（Phase 11 原型为进程内元数据服务，
消息模型对齐 Raft）：

```text
JOIN（注册节点）→ 分配 ShardGroup
GET shard topology → 返回 slot → shard → leader
LEADER-CHANGE → 更新分片 leader（故障转移后）
```

1. `NodeRegistry`：节点注册/注销；
2. `ShardRegistry`：分片组与 leader；
3. `TopologyManager`：slot 表与拓扑快照；
4. `MetadataServer`：进程内 join/query/update。

## Alternatives

1. ZooKeeper：成熟但外部依赖；
2. 静态配置：无故障转移。

## Consequences

**优点：** 无外部依赖、模型统一。
**缺点：** 元数据服务本身需高可用（后续以独立 Raft 组承载）。
**风险：** 元数据与数据分片状态不一致 → 版本号 + 重路由校验。

## Implementation

- `io.tieringkv.cluster.metadata`：MetadataServer / ClusterMetadata /
  NodeRegistry / ShardRegistry / TopologyManager。
