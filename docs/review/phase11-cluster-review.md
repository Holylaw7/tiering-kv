# Phase 11 评审报告：分布式集群（Cluster Review）

日期：2026-08-10 · 阶段：Phase 11 ✅

## 1. 架构

```text
Client → ClusterClient（slot 路由）→ MetadataServer（拓扑）
    → Shard Leader（ClusterNode）
        → Raft Group（Follower / Candidate / Leader）
            → ReplicatedStorageEngine
                → TieringStorageEngine（MemTable / WAL / SSTable）
```

架构对齐 Redis Cluster / TiKV 存储层的基础模型：

- 数据面与元数据面分离：slot 路由走元数据快照，写请求只发给 shard leader；
- 存储核心零改动：复制通过 `ReplicatedStorageEngine` 适配器挂在
  `StorageEngine` SPI 之下，MemTable / WAL / SSTable 均未修改；
- 异步隔离：Raft tick / 复制在独立调度线程执行，不阻塞命令线程。

详细设计见
[distributed-architecture.md](../architecture/distributed-architecture.md)。

## 2. ADR 决策

| ADR | 决策 | 结论 |
| --- | --- | --- |
| [ADR-0035](../adr/ADR-0035-cluster-sharding-strategy.md) | 16384 hash slot（CRC16/CCITT），对比 consistent hash / range | 分布均匀（±0.1%）、可迁移 |
| [ADR-0036](../adr/ADR-0036-metadata-service-design.md) | Raft 化元数据，对比 ZK / 静态配置 | 单点故障可自愈 |
| [ADR-0037](../adr/ADR-0037-replication-model.md) | Raft 复制，对比 async / semi-sync | 多数派提交，无脑裂 |
| [ADR-0038](../adr/ADR-0038-failure-detection-strategy.md) | 心跳 + 随机化选举超时（100–180ms） | 选举 <5s 目标达成 |

## 3. 实现

### sharding（10 测试）

`HashSlotRouter`（CRC16/CCITT + 16384 slot）、`SlotTable`（slot→shard）、
`ShardGroup` / `ShardId` / `PartitionKey`；同键恒定路由、分布与碰撞测试。

### metadata（10 测试）

`MetadataServer`（JOIN / LEAVE / createShard / updateLeader）、
`NodeRegistry`、`ShardRegistry`、`TopologyManager`（slot 表 + 分片组联合
查询）。

### raft（21 测试）

`RaftNode`（Follower/Candidate/Leader + 心跳 + 日志复制 + commit/apply）、
`LeaderElection`（随机化超时）、`ReplicationManager`（nextIndex /
matchIndex + 失败回退）、`LogEntry` / `Term` / 投票与追加请求响应。

实现要点与修复记录：

- 日志索引 0 基，`commitIndex` / `lastApplied` 初始 `-1`；
- 多数派 = `peers.size()/2 + 1`（peers 含自身）；
- 复制发送在锁外执行，锁内 apply 结果，避免锁内调用 peer 造成的锁反转；
- `ReplicationManager.matchIndex` 初始 `-1`，修复"无多数派却提交"缺陷
  （此前 0 会被误判为已匹配第 0 条日志）；
- 挂起节点不再参与 tick（选举/心跳），模拟崩溃语义；
- `propose` 在失去领导权且未提交时快速失败，避免客户端无限等待；
- 测试辅助 `awaitLeader` 等待集群 term 稳定（所有活跃节点同 term 且无
  CANDIDATE），消除"旧 term leader + 新 term 选举"竞态；
- 测试修正：`twoOfThreeClusterSurvivesSingleFailure` /
  `leaderContinuesAfterReplicaCrash` 原条件会挂起两个 follower，改为仅
  模拟单 follower 故障。

### replication adapter

`ReplicatedStorageEngine`：写路径 `Raft propose → 多数派 → apply 本地
引擎`；命令编码（PUT/DELETE + key/value/ttl）自包含；读走 leader 本地
引擎。`ClusterNode` / `ClusterClient` 提供节点封装与路由客户端（leader
变化时重试一次）。

## 4. 测试

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| ShardingTest | 10 | ✅ |
| MetadataTest | 10 | ✅ |
| RaftTest | 21 | ✅ |
| FailoverTest | 9 | ✅ |
| ClusterIntegrationTest（3 节点） | 1 | ✅ |
| 集群新增合计 | 51 | ✅（验收 ≥50） |
| 全量回归（Phase 1–11） | 288 | ✅ 0 失败 |

集成场景：3 节点集群 `SET user:1 value` → 全节点复制 → 杀 leader →
新 leader 选举 → `GET` 返回正确值。

## 5. 基准（进程内原型）

见 [cluster-report.md](../benchmark/cluster-report.md)。

| 指标 | 结果 | 目标 | 结论 |
| --- | --- | --- | --- |
| 单分片复制写 | 154K ops/s，P99=0.027ms | 提供基线 | ✅ |
| 单分片读 | 750K ops/s，P99=4μs | 提供基线 | ✅ |
| 路由开销 | ~23ns/op（194 vs 171 ns/op） | 可忽略 | ✅ |
| 三 shard 分布 | 33.2% / 33.2% / 33.3% | 均匀 | ✅ |
| 复制写延迟（直连） | P99=0.058ms | 提供基线 | ✅ |
| 复制滞后 | 13–35ms（P99≤35ms，心跳周期约束） | 提供基线 | ✅ |
| 选举时间 | 124–310ms（多次运行观测区间） | <5s | ✅ 余量 >16× |

## 6. 限制（如实声明）

1. Raft 消息为进程内直接调用，无 TCP 传输层（TD-023）；
2. Raft 日志为内存态，无磁盘持久化与快照（TD-022）；
3. 复制滞后受心跳周期约束（观测 13–35ms），提交后未立即补发 commitIndex
   （TD-024）；
4. 分片为静态拓扑，无在线 slot 迁移 / 数据搬迁（TD-025）；
5. 元数据服务为进程内单机实现，Raft 化设计已记录但未落地为独立服务；
6. 基准不含真实网络延迟与故障注入（进程内 suspend/close 模拟崩溃）。

## 7. 下一步（Phase 12）

- Raft Log Store（磁盘持久化）+ Snapshot / 日志截断；
- TCP RPC 传输（Vote / AppendEntries 消息序列化 + 超时重试）；
- 提交后立即补发 commitIndex，降低复制滞后；
- 动态 slot 迁移与多 shard 数据搬迁；
- 元数据服务 Raft 化落地与网关节点。

## 8. 外部评审意见与处置（2026-08-10）

评审结论：分片（16384 hash slot）、元数据拆分（NodeRegistry /
ShardRegistry / TopologyManager）、Raft 修复（matchIndex 初始 -1、
挂起节点停止参与选举、propose 失去领导权快速失败）、
ReplicatedStorageEngine 上层包装（不改存储核心）均确认正确；
复制写 145–154K ops/s、读 750–840K ops/s、选举 124–310ms 符合预期。

评审指出 Phase 11 不足（均已在 Phase 11 完成时如实登记）：

| 评审项 | 现状 | 处置 |
| --- | --- | --- |
| Raft Log 不持久化（term/vote/log/commitIndex 重启丢失） | TD-022 | Phase 12 Raft Log Store + Snapshot |
| Snapshot 缺失（日志无限增长，恢复变慢） | TD-022 | Phase 12 Snapshot + 日志截断 |
| RPC 为进程内对象调用（无 TCP/protobuf/correlation） | TD-023 | Phase 12 Netty RPC + 消息序列化 |
| Slot 迁移缺失（静态分片） | TD-025 | Phase 12 slot migration protocol |

评审还前瞻确认元数据演进方向：Metadata → Raft Cluster（Topology State），
对齐 etcd / PD（TiDB）；已写入 Phase 12 计划。
