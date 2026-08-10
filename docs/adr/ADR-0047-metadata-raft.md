# ADR-0047: Metadata Raftization

## Status

Accepted

## Context

Phase 11/12 的 MetadataServer 为单机进程内实现：节点注册、分片拓扑、
slot 归属全部内存态；进程崩溃即丢失，且无复制。

## Problem

- 元数据必须可复制、可故障转移（leader 失效后仍可用）；
- 需要统一的命令模型（JOIN / CREATE_SHARD / UPDATE_LEADER /
  ASSIGN_SLOTS / MIGRATION_STATUS）；
- 复用现有 RaftNode，不重复实现共识。

## Options

1. **静态配置 + 外部协调（ZooKeeper/etcd）**：依赖外部系统；
2. **独立元数据 Raft 组（选定）**：复用 RaftNode + 序列化命令状态机，
   状态包含 NodeRegistry / SlotTable / Topology / migration status；
3. **与数据分片共用 Raft 组**：耦合数据与元数据，扩容复杂，否决。

## Decision

采用 **独立 Metadata Raft Group**：

```text
Client → MetadataClient（路由到 Metadata Leader）
  → MetadataRaftGroup（3 节点 RaftNode）
      → 状态机 apply：MetadataCommand（JOIN/CREATE_SHARD/
        UPDATE_LEADER/ASSIGN_SLOTS/MIGRATION_STATUS）
      → MetadataState（NodeRegistry + SlotTable + ShardRegistry +
        migration status）
```

1. 命令二进制编解码（`MetadataCodec`），仅 leader 可写（通过 propose）；
2. 读走 leader（原型线性一致语义）；
3. leader 故障 → 选举新 leader → MetadataClient 重路由，元数据仍可用；
4. 兼容层：`MetadataRaftServer` 提供与 `MetadataServer` 近似的 API。

## Consequences

**优点：** 元数据高可用、命令可审计、复用已验证的 Raft；
**缺点：** 写路径增加一次 Raft 往返（元数据写频率低，可接受）；
**风险：** 元数据状态与数据分片拓扑并发变更 → 命令顺序由 Raft 日志
保证。

## Future Evolution

- 元数据读缓存 + 版本号（topology epoch）；
- 与 slot 迁移联动（迁移状态入元数据命令流）；
- 跨机房元数据组。
