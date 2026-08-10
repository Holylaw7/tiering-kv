# Phase 12 评审报告：分布式生产化（Distributed Productionization Review）

日期：2026-08-10 · 阶段：Phase 12 ✅

## 1. 架构变化

```text
Client → ClusterClient（slot 路由）→ MetadataServer
  → Shard Leader（ClusterNode）
      → RaftNode
          ├── RaftLog（文件分段 + CRC + SYNC/ASYNC/NONE）
          ├── RaftPersistentState（term/votedFor/commitIndex）
          ├── SnapshotManager（压缩 + InstallSnapshot）
          └── RaftTransport
                ├── LocalRaftTransport（测试/回退）
                └── NettyRaftTransport（TCP）
      → ReplicatedStorageEngine → TieringStorageEngine
```

存储核心（MemTable/WAL/SSTable）零改动；持久化、网络、快照全部以
适配器/装饰器形式叠加在 Raft 层。

## 2. ADR 列表

| ADR | 决策 |
| --- | --- |
| [ADR-0039](../adr/ADR-0039-raft-log-storage-format.md) | RaftLog 二进制格式 + 分段 + CRC32C + SYNC/ASYNC/NONE |
| [ADR-0040](../adr/ADR-0040-raft-snapshot-strategy.md) | Snapshot + 日志压缩 + InstallSnapshot 追赶 |
| [ADR-0041](../adr/ADR-0041-distributed-rpc-design.md) | Netty TCP RPC：帧协议 + 关联/超时/重试/连接复用 |
| [ADR-0042](../adr/ADR-0042-replication-lag-optimization.md) | CommitNotifier 立即补发 commitIndex |
| [ADR-0043](../adr/ADR-0043-slot-migration-strategy.md) | 在线迁移状态机 + checkpoint 续传 + CRC 校验 |

## 3. 实现

### RaftLog（21 测试）

`FileRaftLog` / `LogSegment` / `RaftLogWriter` / `RaftLogReader` /
`RaftLogRecovery`：MAGIC/VERSION/TERM/INDEX/COMMAND_TYPE/DATA/CRC32C；
段滚动、尾部损坏截断、段间连续性校验、`installSnapshot` 前缀压缩、
`base.idx` 持久化空日志首索引；`MemoryRaftLog` 保留测试路径。

### 持久状态（含在 RaftLog 测试）

`RaftPersistentState`：term/votedFor/commitIndex + CRC；term/votedFor
变更强制落盘，commitIndex 缓冲写入（由日志重放推导），消除每提交 fsync
瓶颈（TCP 提交 155 → 1,359 ops/s）。

### Snapshot（12 + 2 集成测试）

`SnapshotManager` / `SnapshotWriter` / `SnapshotReader` /
`SnapshotMetadata`：原子写（tmp + move）+ CRC 校验；RaftNode 日志超
1024 条自动压缩；重启 = 快照恢复 + 剩余日志重放；落后 follower 通过
InstallSnapshot 追赶。

### Netty RPC（19 测试）

`RpcServer` / `RpcClient` / `RpcCodec` / `RequestId` / `RpcFrame` /
`RaftMessageCodec`：长度前缀帧、请求关联、超时、幂等重试、连接复用、
断线重连；三类 Raft 消息；`RaftTransport` 抽象使 RaftNode 不感知传输。

### 复制优化（5 测试）

`CommitNotifier`（提交后立即补发，去重）+ `ReplicationTracker` /
`FollowerProgress`（nextIndex/matchIndex/lastAck）：复制滞后从
13–35ms 降至 **<1ms**（目标 <5ms ✅）。

### Slot 迁移（11 测试）

`SlotMigrationManager` / `MigrationTask` / `MigrationCheckpoint` /
`MigrationState`：INIT→COPYING→VERIFYING→SWITCHING→DONE；
checkpoint 文件持久化（CRC）、断点续传、源/目标 CRC 比对、SlotTable
原子切换、切换后清理源（无数据丢失）。

### TCP 集群集成（3 测试）

3 节点真实 TCP：SET → 复制 → 杀 leader → 选举 → GET 正确；
Raft 日志/状态重启恢复（term/commitIndex/数据完整）；
复制滞后断言 <100ms（实测 0ms）。

## 4. 测试统计

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| RaftLogPersistenceTest | 21 | ✅ |
| SnapshotTest | 12 | ✅ |
| RpcTest | 19 | ✅ |
| SlotMigrationTest | 11 | ✅ |
| ReplicationOptimizationTest | 5 | ✅ |
| RaftNodeSnapshotIntegrationTest | 2 | ✅ |
| TcpClusterIntegrationTest | 3 | ✅ |
| DistributedProductionBenchmarkTest | 4 | ✅ |
| Phase 12 新增合计 | 77 | ✅ |
| 全量回归（Phase 1–12） | 369 | ✅ 0 失败 |

验收：RaftLog ≥15 ✅（21）、Snapshot ≥10 ✅（12+2）、RPC ≥15 ✅（19）、
迁移 ≥10 ✅（11）、3 节点真实 TCP 集成 ✅。

## 5. 基准（distributed-production-report.md）

| 指标 | 结果 | 对比 |
| --- | --- | --- |
| RaftLog ASYNC append | 102K ops/s，P99=27μs | 提供基线 |
| TCP 提交（3 节点） | 1,359 ops/s，P50=0.65ms / P99=2.16ms | Phase 11 进程内 154K ops/s（真实网络 + 持久化成本） |
| 复制滞后 | 0ms（P50/P99/Max） | Phase 11 13–35ms，目标 <5ms ✅ |
| RPC | 9.3K ops/s，P50=100μs，单连接复用 | 提供基线 |
| 迁移 | 16.1MB/s；断点续传 549ms/90K | 提供基线 |

## 6. 限制（如实声明）

1. 复制为同步串行 propose（2 次 RPC 串行）→ 批量/并行复制（TD-026）；
2. 迁移每批重建源快照迭代（MemTable 快照成本）→ 单次迭代 + 游标
   checkpoint（TD-027）；
3. RPC 无 TLS/认证/限流（TD-028）；
4. 元数据服务仍为单机实现，Raft 化落地留后续（TD-029）；
5. 快照传输为单次全量发送，无分块/限速；
6. 基准为单机回环，未测跨机网络与故障注入。

## 7. 下一步（Phase 13）

- 批量/并行 AppendEntries（group commit）提升 TCP 提交吞吐；
- 迁移单次迭代 + 游标 checkpoint；
- RPC TLS/认证/限流；
- 元数据服务 Raft 化落地与网关节点；
- 跨机部署验证与故障注入（partition / 丢包）。
