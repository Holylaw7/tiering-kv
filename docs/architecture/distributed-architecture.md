# 分布式架构（Distributed Architecture）

状态：✅ 原型完成（Phase 11）→ 分布式生产化（Phase 12）→ 分布式优化（Phase 13）

## 1. 拓扑

```text
Client
  │
  ▼
Gateway（连接 + 路由 + 拓扑缓存）
  │
  ▼
Cluster Router（CRC16(key) % 16384 → slot → shard）
  │
  ▼
Metadata Service（shard → 节点组 → leader，Raft 化元数据）
  │
  ▼
Shard Leader → Raft Group（3 节点）
  │
  ▼
ReplicatedStorageEngine（适配器，不改存储核心）
  │
  ▼
TieringStorageEngine（Phase 1–10 单机引擎）
```

## 2. 节点类型

- **Gateway**：客户端连接、请求路由、拓扑缓存；
- **Metadata**：维护 `shard → node group → leader`（ADR-0036）；
- **Storage**：复用 TieringStorageEngine；写入经 Raft 复制（ADR-0037）；
  原子字符串命令（TTL/INCR/APPEND/GETSET/GETDEL/SETNX/EXPIRE/PERSIST）
  经 Raft ATOMIC 命令在 apply 阶段确定性执行（ADR-0352，TD-081 关闭）。

## 3. 数据路径

```text
写：Client → Router(slot) → Leader → Raft append → 多数派 ack
    → apply 本地存储 → 应答
原子写：同上，ATOMIC 命令（op 码 + key/value + delta/expireAt）在
    apply 阶段执行并回传结果；领域错误（如 INCR 非整数）回传而不悬挂
读：Leader/Replica 本地读（原型语义，强一致读留后续）
故障：Leader 崩溃 → 选举（<5s）→ 元数据更新 → 客户端重路由
```

> ADR-0352 语义边界：`update(UnaryOperator)` 不可序列化，采用
> Leader 本地 RMW + 复制最终值（保留 TTL）；TTL/版本查询本地读取。

## 4. 分片

16384 hash slots（Redis Cluster 风格，ADR-0035）；slot 表可重映射
（rebalance 友好）。

## 5. Raft 持久化（Phase 12，ADR-0039/0040）

```text
RaftNode
  ├── RaftLog（FileRaftLog：分段文件 + MAGIC/VERSION/TERM/INDEX/
  │   COMMAND_TYPE/DATA/CRC32C，SYNC/ASYNC/NONE）
  ├── RaftPersistentState（term / votedFor / commitIndex，CRC + force）
  └── SnapshotManager（快照文件 + InstallSnapshot + 日志压缩）
```

- 崩溃恢复：加载段 → CRC 校验 → 重放合法条目 → 截断损坏尾部；
- 快照：`lastIncludedIndex/lastIncludedTerm + 状态数据 + checksum`，
  超过阈值（1024 条）自动创建并压缩日志；重启 = 快照恢复 + 剩余日志重放；
- 落后 follower：leader 发送 InstallSnapshot 快速追赶。

## 6. 网络传输（Phase 12，ADR-0041）

```text
RaftTransport
  ├── LocalRaftTransport（Phase 11 进程内，测试/回退）
  └── NettyRaftTransport（生产）
        ├── RpcServer（解码 → 本地 RaftNode.receive → 响应）
        └── RpcClient（连接复用 + RequestId 关联 + 超时 + 幂等重试）
```

线协议：`LENGTH | REQUEST_ID(8B) | TYPE(1B) | PAYLOAD`；消息覆盖
AppendEntries / RequestVote / InstallSnapshot。

## 7. 复制优化与迁移（Phase 12，ADR-0042/0043）

- 复制优化：CommitNotifier 在 commitIndex 推进后立即补发心跳，
  复制滞后从 13–35ms 降至 <1ms（目标 <5ms ✅）；
  ReplicationTracker / FollowerProgress 跟踪 nextIndex / matchIndex /
  lastAck；
- Slot 迁移：`INIT → COPYING → VERIFYING → SWITCHING → DONE`，
  checkpoint 持久化可断点续传，VERIFYING 对源/目标做 CRC 比对，
  SWITCHING 原子更新 SlotTable 后清理源数据（无数据丢失）。

## 8. 当前边界

- RPC 单连接串行、无 TLS/认证；
- 复制为同步串行 propose（批量/并行复制留后续）；
- 迁移为存量复制模型（增量/双写留后续）；
- 元数据服务单机（Phase 13 已 Raft 化，见 §9）。

## 9. 分布式优化（Phase 13，ADR-0044~0047）

```text
RaftNode（批量/流水线复制）
  ├── RaftReplicationConfig（batch/bytes/flush/inflight）
  ├── 日志镜像缓存（持锁路径零文件读）
  └── ReplicationTracker（inflight/lastSent）
SlotMigrationManager → MigrationCursor（单次扫描 + slot-{start}.cursor）
RpcServer/RpcClient → TLS + RpcAuthInterceptor + TokenBucket
MetadataClient → MetadataRaftGroup → 每副本 MetadataState
```

- 复制：批量 AppendEntries + 多 in-flight + group commit，TCP 9.2K ops/s；
- 迁移：游标单次扫描 + PAUSED/续传，1KB 负载 244.8MB/s；
- 安全：TLS PEM + Token 认证（含过期）+ 限流；
- 元数据：独立 Raft 组，leader 故障转移 115ms，状态机每副本独立；
- 部署：gateway / metadata / storage 角色与 YAML 配置（见
  docs/deployment/distributed-deployment.md）。
