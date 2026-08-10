# Phase 16 评审报告：Multi-Raft 架构演进

Phase 16 · 2026-08-10

## 1. Architecture

```text
Client → ClusterRouter → RegionRouter → Region → RaftGroup → StorageEngine
```

- 路由单元由 ShardId 升级为 Region（键范围 + epoch + 状态）；
- 每个 Region 独立 Raft 组（MultiRaftNode + RaftGroupManager），
  共享单端口 MultiRaftEndpoint（组前缀路由），日志/状态/快照按组隔离；
- 零拷贝写路径（RawMutation 所有权转移）解决迁移拷贝开销；
- PlacementManager 提供分布/均衡/leader 转移（无自动 rebalance）；
- 混沌验证覆盖 Region 级故障隔离；Docker 跨机部署产物齐备。

## 2. ADR

| ADR | 决策 | 评价 |
| --- | --- | --- |
| 0057 Region 模型 | 键范围 + confVer/version 纪元 + 分裂/合并 + 旧路由拒绝 | ✅ |
| 0058 Multi-Raft | 单端口共享端点 + 组前缀路由 + 组隔离 | ✅ |
| 0059 零拷贝写路径 | RawMutation 所有权转移 + applyRawBatch | ✅ |
| 0060 放置控制 | 分布/均衡/leader 转移；自动 rebalance 暂缓 | ✅ |

## 3. Implementation

- Region：Region/RegionEpoch/RegionState/RegionManager（TreeMap 路由 +
  split/merge + tombstone 审计 + epoch guard）；
- Multi-Raft：MultiRaftNode / RaftGroupManager（含持久化组创建）/
  MultiRaftEndpoint（单端口多组）/ MultiRaftTransport（RaftTransport
  兼容，RaftNode API 零改动）；
- Zero-copy：RawMutation / KeyValueEntry owned 构造 / applyRawBatch
  （平面桶分组）/ SkipList.putAndGetOld / StreamingMigrator 切换；
- Placement：PlacementManager + RegionManager.transferLeader；
- Observability：RegionMetricsRegistry + INFO REGIONS；
- ClusterMain：真实 3 JVM 拓扑入口（compose 可直接启动）。

## 4. Tests

新增 138 项（Phase 15 基线 650）：

| 模块 | 新增 | 结果 |
| --- | --- | --- |
| Region（Part 1） | 34 | ✅ |
| Multi-Raft（Part 2） | 32 | ✅ |
| Zero-Copy（Part 3） | 21 | ✅ |
| Chaos（Part 4） | 21（含 Raft 回归） | ✅ |
| Placement + 可观测性（Part 5） | 23 | ✅ |
| 基准 | 6 | ✅ |

全量回归 **788/788 全绿**。

## 5. Benchmark

| 指标 | Phase 15 | Phase 16 | 目标 | 状态 |
| --- | --- | --- | --- | --- |
| 迁移 100B | 59.8 MB/s | 82.7 MB/s | >100 | ❌ 未达 |
| 迁移 1KB | 173.3 MB/s | 223.1 MB/s | >300 | ❌ 未达 |
| 迁移 10KB | 589.8 MB/s | 631.0 MB/s | — | ✅ |
| Multi-Raft 1/2/4 组 | — | 92~110/222~314/404~841 K ops/s | 线性扩展 | ✅（2~3.4×/3.7~9.2×） |
| TCP 单端口 P99 | — | 0.551 ms | — | ✅ |
| 故障恢复 p50 | — | 183 ms | <5 s | ✅ |

## 6. Chaos

- ChaosClusterTest 20 项：延迟/丢包/分区/磁盘慢/双组击杀/重启追平/
  混合故障/epoch 保护，三轮稳定；
- **发现并修复真实 Raft 缺陷**：新 leader 以非空日志当选后不回填滞后
  follower（心跳拒绝未回退 nextIndex），修复 + 回归测试；
- 环境限制：Windows 无 Docker 守护进程与 tc netem；容器混沌脚本与
  compose 已交付，待 Linux+Docker 环境执行。

## 7. Limitations（不隐藏）

1. 100B/1KB 迁移目标未达（82.7/223.1 vs 100/300 MB/s）：剩余每条目
   固定开销（归并/CRC/分段锁），并行迁移为下一阶段；
2. leader 转移仅更新元数据 epoch，未触发真实 Raft 层交接；
3. 跨机容器混沌未在本机执行（环境限制），仅交付产物 + 等价本地验证；
4. Region 分裂/合并未与存储数据搬迁联动（路由层已就绪）；
5. ClusterMain 无 Redis 网关（仅 Raft RPC + 存储）。

## 8. Next Phase

- 并行迁移/按段写入，攻关 100B >100MB/s；
- Region 分裂数据搬迁（split 与 Multi-Raft 联动）；
- 真实 Raft leader 交接（transfer 触发 step-down + 选举）；
- Docker+Linux 跨机 netem 混沌执行；Redis 网关接入 ClusterMain。

**定位**：Tiering-KV 已具备 Region + Multi-Raft + Placement 架构骨架，
Phase 16 除小负载迁移目标外全部达成。
